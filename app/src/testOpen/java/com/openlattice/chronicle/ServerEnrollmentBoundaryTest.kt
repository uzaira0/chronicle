package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ServerEnrollmentBoundaryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb

    @Before fun setUp() {
        val prefs = context.getSharedPreferences("open-server-boundary", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
    }

    @After fun tearDown() {
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        db.close()
    }

    @Test fun openParticipantCanOnlyWithdrawFromAnExistingServer() {
        val add = Robolectric.buildActivity(ServerEnrollmentActivity::class.java).setup()
        assertTrue(add.get().isFinishing)
        add.destroy()

        val id = db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = "11111111-1111-1111-1111-111111111111", participantId = "p1", sourceDeviceId = "d1", apiKey = "key"))
        val controller = Robolectric.buildActivity(ServerEnrollmentActivity::class.java,
            Intent(context, ServerEnrollmentActivity::class.java).putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, id)).setup()
        val activity = controller.get()
        try {
            assertFalse(activity.isFinishing)
            listOf(R.id.serverSaveButton, R.id.serverEnabledSwitch, R.id.serverHealthButton,
                R.id.serverSigningSecretTextLayout).forEach { assertEquals(View.GONE, activity.findViewById<View>(it).visibility) }
            listOf(R.id.serverNameText, R.id.serverUrlText, R.id.serverStudyIdText,
                R.id.serverParticipantIdText).forEach { assertFalse(activity.findViewById<View>(it).isEnabled) }
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.serverDeleteButton).visibility)
        } finally {
            controller.destroy()
            val executor = ServerEnrollmentActivity::class.java.getDeclaredField("executor").apply { isAccessible = true }
                .get(activity) as ExecutorService
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
