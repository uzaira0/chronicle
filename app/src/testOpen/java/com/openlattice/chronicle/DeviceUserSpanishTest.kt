package com.openlattice.chronicle

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UserQueueEntry
import com.openlattice.chronicle.ui.SettingsHomeFragment
import com.openlattice.chronicle.ui.awaitDashboardUi
import com.openlattice.chronicle.utils.Utils
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * A phone running in Spanish shows Spanish device-user choices but stores, uploads and exports the English
 * values ("Study participant", "Someone else", "Not set"). A phone still holding a value an older release
 * wrote ("Target child") shows the matching choice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "es")
class DeviceUserSpanishTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    class Host : AppCompatActivity() {
        override fun onCreate(state: Bundle?) { setTheme(R.style.AppTheme); super.onCreate(state) }
    }
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun install() {
        AuditStores.install(context, enrolled = true)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        StorageAdmission.clear(context)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        // The settings choices unlock only while the identify-user prompt can reach the participant.
        Utils.createNotificationChannel(context)
    }

    private fun queuedUsers() = ChronicleDb.getInstance(context).userQueueEntryData().getUserTimestamps().map { it.user }

    @Test fun unlockPromptShowsSpanishAndStoresEnglish() {
        for ((button, stored, shown) in listOf(
            Triple(R.id.participant_user_btn, "Study participant", "Participante del estudio"),
            Triple(R.id.someone_else_user_btn, "Someone else", "Otra persona"),
        )) {
            install()
            val controller = Robolectric.buildActivity(UserIdentificationActivity::class.java).setup()
            val activity = controller.get()
            try {
                val view = activity.findViewById<Button>(button)
                view.performClick()
                awaitDashboardUi { activity.isFinishing }
                assertEquals(stored, EnrollmentSettings(context).getCurrentUser())
                assertEquals(listOf(stored), queuedUsers())
                assertEquals(shown, view.text.toString())
                assertEquals("El usuario del dispositivo se ha establecido como \"$shown\"", ShadowToast.getTextOfLatestToast())
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun settingsChoicesShowSpanishAndStoreEnglish() {
        install()
        val controller = Robolectric.buildActivity(Host::class.java).create()
        val fragment = SettingsHomeFragment()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start().resume()
        try {
            val group = fragment.requireView().findViewById<MaterialButtonToggleGroup>(R.id.deviceUserGroup)
            for ((button, stored, shown) in listOf(
                Triple(R.id.deviceUserParticipant, "Study participant", "Participante del estudio"),
                Triple(R.id.deviceUserSomeoneElse, "Someone else", "Otra persona"),
                Triple(R.id.deviceUserUnset, "Not set", "Sin definir"),
            )) {
                val view = group.findViewById<Button>(button)
                awaitDashboardUi { view.isEnabled }
                view.performClick()
                awaitDashboardUi { EnrollmentSettings(context).getCurrentUser() == stored }
                assertEquals(stored, queuedUsers().first())
                assertEquals(shown, view.text.toString())
                // Reopening the screen maps the stored English value back to the same choice.
                controller.pause().resume()
                awaitDashboardUi { view.isEnabled && group.checkedButtonId == button }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun legacyStoredValueShowsTheMatchingChoice() {
        install()
        ChronicleDb.getInstance(context).userQueueEntryData()
            .insertEntries(listOf(UserQueueEntry(System.currentTimeMillis(), "Target child")))
        assertEquals("Study participant", EnrollmentSettings(context).getCurrentUser())
        val controller = Robolectric.buildActivity(Host::class.java).create()
        val fragment = SettingsHomeFragment()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start().resume()
        try {
            val group = fragment.requireView().findViewById<MaterialButtonToggleGroup>(R.id.deviceUserGroup)
            awaitDashboardUi { group.checkedButtonId == R.id.deviceUserParticipant }
        } finally { controller.pause().stop().destroy() }
    }
}
