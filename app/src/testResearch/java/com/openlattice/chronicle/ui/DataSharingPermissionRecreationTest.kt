package com.openlattice.chronicle.ui

import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.health.connect.HealthConnectManager
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.descendants
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.device.HealthConnectPlatformGateTest
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.device.healthConnectTestClient
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [HealthConnectPlatformGateTest.GrantedClient::class])
class DataSharingPermissionRecreationTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun installActiveModulesWithMissingAccess() {
        AuditStores.install(context, true, mapOf(
            CollectionModuleId.SENSOR_STEP_COUNTER to CollectionModuleSetting(enabled = true),
            CollectionModuleId.HEALTH_CONNECT to CollectionModuleSetting(enabled = true,
                healthConnectRecordTypes = setOf(HealthConnectRecordType.STEPS)),
        ))
        val app = context as Application
        shadowOf(app).denyPermissions(ModulePermissions.ACTIVITY_RECOGNITION)
        context.getSharedPreferences("runtime_permission_requests", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ALLOWED)
        shadowOf(context.getSystemService(SensorManager::class.java))
            .addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_STEP_COUNTER).build())
        Shadow.extract<ShadowContextImpl>(app.baseContext).setSystemService(Context.HEALTHCONNECT_SERVICE,
            Shadow.newInstanceOf(HealthConnectManager::class.java))
        HealthConnectPlatformGateTest.GrantedClient.value = healthConnectTestClient({ emptySet() })
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
    }

    @After fun close() = onPersistenceWorker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        StorageAdmission.clear(context)
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
    }

    @Test fun healthConnectContinuationSurvivesRotation() = permissionContinuation(false)
    @Test fun healthConnectContinuationSurvivesProcessDeath() = permissionContinuation(true)

    private fun permissionContinuation(processDeath: Boolean) {
        val tap = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SELECT_TAB, R.id.nav_data_sharing)
        var controller = Robolectric.buildActivity(MainActivity::class.java, tap).setup()
        try {
            fun fragment() = controller.get().supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) as? DataSharingFragment
            awaitDashboardUi { (fragment()?.view as? ViewGroup)?.descendants?.filterIsInstance<MaterialButton>()
                ?.any { it.contentDescription == context.getString(R.string.ds_grant_access_cd) && it.isShown } == true }
            val button = (checkNotNull(fragment()).requireView() as ViewGroup).descendants.filterIsInstance<MaterialButton>()
                .single { it.contentDescription == context.getString(R.string.ds_grant_access_cd) }
            assertTrue(button.performClick())
            val request = requireNotNull(shadowOf(controller.get()).lastRequestedPermission)
            assertEquals(listOf(ModulePermissions.ACTIVITY_RECOGNITION), request.requestedPermissions.toList())
            if (processDeath) {
                val saved = Bundle()
                controller.pause().saveInstanceState(saved).stop().destroy()
                controller = Robolectric.buildActivity(MainActivity::class.java, tap)
                    .create(saved).start().restoreInstanceState(saved).resume().visible()
            } else {
                controller.recreate()
            }
            shadowOf(context as Application).grantPermissions(ModulePermissions.ACTIVITY_RECOGNITION)
            controller.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent()
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, request.requestedPermissions)
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    intArrayOf(PackageManager.PERMISSION_GRANTED)))
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull("The granted runtime request must continue to Health Connect", dialog)
            assertEquals(context.getString(R.string.health_connect_disclosure_title),
                dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle).text)
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun permanentlyDeniedRuntimeAccessOpensPackageSettingsWithoutRequestLoop() {
        context.getSharedPreferences("runtime_permission_requests", Context.MODE_PRIVATE).edit()
            .putBoolean(ModulePermissions.ACTIVITY_RECOGNITION, true).commit()
        val settings = ComponentName("android", "Settings")
        shadowOf(context.packageManager).addActivityIfNotPresent(settings)
        shadowOf(context.packageManager).addIntentFilterForActivity(settings,
            IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("package")
            })
        val controller = Robolectric.buildActivity(MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SELECT_TAB, R.id.nav_data_sharing)).setup()
        try {
            fun buttons() = controller.get().findViewById<android.view.ViewGroup>(android.R.id.content)
                .descendants.filterIsInstance<MaterialButton>()
            awaitDashboardUi { buttons().any { it.contentDescription == context.getString(R.string.ds_open_app_settings) && it.isShown } }
            assertTrue(buttons().single { it.contentDescription == context.getString(R.string.ds_open_app_settings) }.performClick())
            val intent = shadowOf(controller.get()).nextStartedActivity
            assertNotNull("permanent denial needs an app settings route", intent)
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent?.action)
            assertEquals("package:${context.packageName}", intent?.dataString)
            assertEquals(null, shadowOf(controller.get()).lastRequestedPermission)
        } finally { controller.pause().stop().destroy() }
    }
}
