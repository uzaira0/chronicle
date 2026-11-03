package com.openlattice.chronicle.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorBuilder

@RunWith(RobolectricTestRunner::class)
class DataSharingRefreshAuditTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    class Host : AppCompatActivity() {
        override fun onCreate(state: Bundle?) { setTheme(R.style.AppTheme); super.onCreate(state) }
    }
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun close() = onPersistenceWorker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        StorageAdmission.clear(context)
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
    }

    @Test fun unchangedAndChangedRefreshRetainModuleControlIdentityAndFocus() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        val fragment = DataSharingFragment()
        withHostedFragment(fragment) { controller ->
            controller.visible()
            val list = fragment.requireView().findViewById<LinearLayout>(R.id.appUsageModuleList)
            fun batteryRow() = (0 until list.childCount).mapNotNull { list.getChildAt(it) as? LinearLayout }.firstOrNull {
                (it.getChildAt(0) as? TextView)?.text?.contains("Battery", ignoreCase = true) == true
            }
            awaitDashboardUi { batteryRow() != null }
            val row = checkNotNull(batteryRow())
            val index = list.indexOfChild(row)
            val toggle = row.getChildAt(1) as MaterialSwitch
            toggle.isFocusableInTouchMode = true
            assertTrue(toggle.requestFocus())
            fun refresh() {
                controller.pause().resume()
                val scope = checkNotNull(fragment.viewLifecycleOwner.lifecycleScope.coroutineContext[Job])
                // The refresh waits in delay after its database children finish.
                awaitDashboardUi { scope.children.singleOrNull { it.isActive }?.children?.none() == true }
            }
            refresh()
            assertSame(row, list.getChildAt(index))
            assertTrue(toggle.hasFocus())
            onPersistenceWorker {
                val store = CollectionLoopStore.of(context)
                store.save(listOf(store.loadAll().getValue(CollectionModuleId.BATTERY_TELEMETRY)
                    .copy(decision = ParticipantDecision.DECLINED)))
            }
            refresh()
            awaitDashboardUi { !toggle.isChecked }
            assertSame(row, list.getChildAt(index))
            assertFalse(toggle.isChecked)
            assertTrue(toggle.hasFocus())
        }
    }

    @Test fun declinedRequiredChoicePausesAcceptedAppAndSensorLabels() = pausedLabels(R.string.ds_status_paused_required)
    @Test fun lowStoragePausesAcceptedAppAndSensorLabels() = pausedLabels(R.string.ds_status_paused_storage)

    @Test fun declinedRequiredChoiceSnapshotShowsPausedWithZeroActive() = runBlocking {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker {
            val store = CollectionLoopStore.of(context)
            store.save(listOf(store.loadAll().getValue(CollectionModuleId.USAGE_EVENTS).copy(decision = ParticipantDecision.DECLINED)))
        }
        val summary = DashboardDataRepository.load(context).collection
        assertEquals(context.getString(R.string.ds_status_paused_required) + "\n" +
            context.getString(R.string.collection_status_counts, 0, 0, 0), summary.message)
        assertEquals(0, summary.active)
    }

    private fun pausedLabels(message: Int) {
        val sensor = CollectionModuleId.SENSOR_ACCELEROMETER
        AuditStores.install(context, enrolled = true, mapOf(sensor to CollectionModuleSetting(enabled = true)))
        shadowOf(context.getSystemService(SensorManager::class.java))
            .addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build())
        onPersistenceWorker {
            val store = CollectionLoopStore.of(context)
            if (message == R.string.ds_status_paused_required) {
                store.save(listOf(store.loadAll().getValue(CollectionModuleId.USAGE_EVENTS)
                    .copy(decision = ParticipantDecision.DECLINED)))
            }
            ResearchPersistenceGate.initialize(context)
            if (message == R.string.ds_status_paused_storage) assertFalse(StorageAdmission.allowed(context, 0))
        }
        val fragment = DataSharingFragment()
        withHostedFragment(fragment) { controller ->
            controller.visible()
            for ((listId, name) in listOf(R.id.appUsageModuleList to "Battery", R.id.sensorToggleList to "Accelerometer")) {
                val list = fragment.requireView().findViewById<LinearLayout>(listId)
                fun label() = (0 until list.childCount).mapNotNull { (list.getChildAt(it) as? LinearLayout)?.getChildAt(0) as? TextView }
                    .firstOrNull { it.text.contains(name, ignoreCase = true) }
                awaitDashboardUi { label()?.text?.contains(context.getString(message)) == true }
                val text = checkNotNull(label()).text
                assertTrue(text.toString(), text.contains(context.getString(message)))
                assertFalse(text.contains("collecting", ignoreCase = true))
            }
        }
    }
}
