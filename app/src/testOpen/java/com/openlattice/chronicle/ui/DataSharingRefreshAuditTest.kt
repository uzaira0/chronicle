package com.openlattice.chronicle.ui

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.layout.TestStores
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class DataSharingRefreshAuditTest {
    class Host : AppCompatActivity() {
        override fun onCreate(state: Bundle?) { setTheme(R.style.AppTheme); super.onCreate(state) }
    }
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun bind(fragment: DataSharingFragment, snapshot: DashboardSnapshot) {
        DataSharingFragment::class.java.getDeclaredMethod("bindAll", android.view.View::class.java, DashboardSnapshot::class.java)
            .apply { isAccessible = true }.invoke(fragment, fragment.requireView(), snapshot)
    }
    @Test fun unchangedAndChangedRefreshRetainModuleControlIdentityAndFocus() = runBlocking {
        TestStores.install(context, enrolled = true)
        val controller = Robolectric.buildActivity(Host::class.java).create()
        val host = controller.get(); val fragment = DataSharingFragment()
        host.supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start().visible()
        val snapshot = DashboardDataRepository.load(context)
        bind(fragment, snapshot)
        val list = fragment.requireView().findViewById<LinearLayout>(R.id.appUsageModuleList)
        val batteryRow = (0 until list.childCount).map { list.getChildAt(it) as LinearLayout }.first {
            (it.getChildAt(0) as android.widget.TextView).text.contains("Battery", ignoreCase = true)
        }
        val batteryIndex = list.indexOfChild(batteryRow)
        val toggle = batteryRow.getChildAt(1) as SwitchMaterial
        toggle.isFocusableInTouchMode = true; assertTrue(toggle.requestFocus())
        bind(fragment, snapshot)
        assertSame(batteryRow, list.getChildAt(batteryIndex));assertTrue(toggle.hasFocus())
        val updated = snapshot.copy(collectionModules = snapshot.collectionModules.map {
            if (it.moduleId == CollectionModuleId.BATTERY_TELEMETRY) it.copy(decision = ParticipantDecision.DECLINED) else it
        })
        bind(fragment, updated)
        assertSame(batteryRow, list.getChildAt(batteryIndex));assertFalse(toggle.isChecked);assertTrue(toggle.hasFocus())
        controller.destroy()
        Unit
    }

}
