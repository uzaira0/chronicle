package com.openlattice.chronicle.collection.identification

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TargetUserRouterLockOrderTest {
    @Test fun alreadyAdmittedRouterCompletesAheadOfQueuedWriterAndFreshRouter() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("router-order", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant").putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        val prefsField = EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
        prefsField.set(EncryptedPrefsHelper, prefs)
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        val dbField = ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        dbField.set(null, db)
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant", sourceDeviceId = "device"))
        val settings = EnrollmentSettings(context)
        val barrier = ResearchPersistenceGate::class.java.getDeclaredField("barrier").apply { isAccessible = true }.get(ResearchPersistenceGate)
        val lock = barrier.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(barrier) as ReentrantReadWriteLock
        val leased = CountDownLatch(1)
        val routeNow = CountDownLatch(1)
        val freshEntered = CountDownLatch(1)
        val admitFresh = CountDownLatch(1)
        val hooked = AtomicBoolean()
        prefsField.set(EncryptedPrefsHelper, Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (Thread.currentThread().name == "fresh-router" && method.name == "getString" &&
                args?.get(0) == STUDY_ID && hooked.compareAndSet(false, true)) {
                freshEntered.countDown()
                check(admitFresh.await(5, TimeUnit.SECONDS))
            }
            method.invoke(prefs, *(args ?: emptyArray()))
        } as SharedPreferences)
        val pool = Executors.newFixedThreadPool(3)
        val admitted = pool.submit {
            ResearchPersistenceGate.withReadLease {
                leased.countDown()
                check(routeNow.await(5, TimeUnit.SECONDS))
                TargetUserRouter.setTargetUser(context, context.getString(R.string.user_unassigned), settings)
            }
        }
        assertTrue(leased.await(5, TimeUnit.SECONDS))
        val fresh = pool.submit { Thread.currentThread().name = "fresh-router"; TargetUserRouter.setTargetUser(context, "other") }
        assertTrue(freshEntered.await(5, TimeUnit.SECONDS))
        val writerThread = AtomicReference<Thread>()
        val writer = pool.submit { writerThread.set(Thread.currentThread()); lock.writeLock().lockInterruptibly(); lock.writeLock().unlock() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (writerThread.get()?.let(lock::hasQueuedThread) != true && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue("writer must be queued behind admitted router", writerThread.get()?.let(lock::hasQueuedThread) == true)
            admitFresh.countDown()
            routeNow.countDown()
            admitted.get(1, TimeUnit.SECONDS)
            fresh.get(5, TimeUnit.SECONDS)
            writer.get(5, TimeUnit.SECONDS)
        } finally {
            // Interrupting the queued writer also releases the deliberate old-order cycle.
            writer.cancel(true)
            admitFresh.countDown(); routeNow.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            dbField.set(null, null); prefsField.set(EncryptedPrefsHelper, null); db.close()
        }
    }
}
