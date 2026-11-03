package com.openlattice.chronicle

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.collection.state.validateEnrollmentModulePartition
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An enrollment left in recovery retries when the participant comes back to the screen, and from its button. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnrollmentRecoveryRetryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb

    @After fun closeStore() = onPersistenceWorker { db.close() }

    @Test fun theRecoveryScreenRetriesOnReturnAndFromItsButton() {
        onPersistenceWorker {
            db = EnrollmentTestStores.install(context)
            val preview = EnrollmentTestStores.enrollmentPreview()
            val settings = preview.manifest.collectionSettings
            reserveEnrollment(context, EnrollmentSubmission(preview = preview,
                partition = validateEnrollmentModulePartition(settings, settings.effectiveEnabledModuleIds(), emptySet(), emptySet()),
                serverUrl = preview.manifest.serverOrigin, enrollmentAccessCode = "retry-test-invitation"))
        }
        val requests = AtomicInteger()
        EnrollmentTestStores.installApi(studyApi { method, _ ->
            assertEquals("enroll", method)
            requests.incrementAndGet()
            throw ChronicleCallException("POST", "request", "", 503)
        }).use {
            val controller = Robolectric.buildActivity(Enrollment::class.java).setup()
            val retry = controller.get().findViewById<Button>(R.id.button)
            fun awaitRequests(count: Int) {
                val deadline = System.currentTimeMillis() + 8_000
                while ((requests.get() < count || retry.visibility != View.VISIBLE) && System.currentTimeMillis() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(count, requests.get())
            }
            try {
                awaitRequests(1)
                assertTrue(retry.isEnabled)
                assertEquals(context.getString(R.string.enrollment_retry), retry.text.toString())
                controller.pause().stop()
                controller.restart().resume()
                awaitRequests(2)
                retry.performClick()
                awaitRequests(3)
            } finally { controller.pause().stop().destroy() }
        }
    }
}
