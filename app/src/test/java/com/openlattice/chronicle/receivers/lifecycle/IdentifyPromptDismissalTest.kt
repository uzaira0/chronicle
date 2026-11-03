package com.openlattice.chronicle.receivers.lifecycle

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.identification.DeviceUser
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.notifications.NOTIFICATION_DELETED_ACTION
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.ui.awaitDashboardUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pilot path 8 on a shared phone: swiping away the "who is using this device" prompt means nobody answered, so the
 * device user goes back to Not set and later usage is not attributed to whoever answered last.
 */
@RunWith(RobolectricTestRunner::class)
class IdentifyPromptDismissalTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val receiver = NotificationDismissedReceiver()

    @Before fun answerThePrompt() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        assertTrue(onPersistenceWorker { TargetUserRouter.setTargetUser(context, DeviceUser.STUDY_PARTICIPANT) } is ModuleResult.Ok)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(NOTIFICATION_DELETED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @After fun tearDown() {
        context.unregisterReceiver(receiver)
        ChronicleDb.getInstance(context).close()
    }

    private fun currentUser() = onPersistenceWorker { EnrollmentSettings(context).getCurrentUser() }
    private fun labels() = onPersistenceWorker { ChronicleDb.getInstance(context).userQueueEntryData().getUserTimestamps().size }

    private fun dismiss(notificationId: Int) = context.sendBroadcast(Intent(NOTIFICATION_DELETED_ACTION)
        .setPackage(context.packageName).putExtra(context.getString(R.string.notification_id), notificationId))

    @Test fun dismissingTheWhoIsUsingPromptResetsTheDeviceUserToNotSet() {
        assertEquals(DeviceUser.STUDY_PARTICIPANT.stored, currentUser())
        dismiss(context.resources.getInteger(R.integer.dismiss_target_user_notification_id))
        awaitDashboardUi { currentUser() == DeviceUser.NOT_SET.stored }
        assertEquals("the reset is recorded as its own label row", 2, labels())
    }
}
