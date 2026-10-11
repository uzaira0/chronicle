package com.openlattice.chronicle.collection.device

import android.app.Application
import android.content.Context
import android.health.connect.HealthConnectManager
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HealthConnectPlatformGateTest {
    private fun client(available: Boolean, granted: Set<String> = emptySet()): HealthConnectClient = Proxy.newProxyInstance(
        HealthConnectClient::class.java.classLoader, arrayOf(HealthConnectClient::class.java)) { _, method, _ ->
        when (method.name) {
            "getFeatures" -> healthConnectTestFeatures(available)
            "getPermissionController" -> Proxy.newProxyInstance(
                androidx.health.connect.client.PermissionController::class.java.classLoader,
                arrayOf(androidx.health.connect.client.PermissionController::class.java)) { _, _, _ -> granted }
            else -> error("unexpected ${method.name}")
        }
    } as HealthConnectClient

    private fun installSystemProvider(app: Application) {
        Shadow.extract<ShadowContextImpl>(app.baseContext)
            .setSystemService(Context.HEALTHCONNECT_SERVICE, Shadow.newInstanceOf(HealthConnectManager::class.java))
    }

    @Implements(value = HealthConnectClient.Companion::class, isInAndroidSdk = false)
    class GrantedClient {
        companion object { lateinit var value: HealthConnectClient }
        @Suppress("UNUSED_PARAMETER")
        @Implementation fun getOrCreate(context: Context, providerPackageName: String): HealthConnectClient = value
    }

    @Test @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 36], shadows = [GrantedClient::class])
    fun allGrantedIgnoresBackgroundPermissionWhenFeatureIsUnavailable() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        installSystemProvider(app)
        val instance = EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = instance.get(EncryptedPrefsHelper)
        instance.set(EncryptedPrefsHelper, app.getSharedPreferences("health-platform-gate", Context.MODE_PRIVATE))
        try {
            val configured = setOf(HealthConnectRecordType.STEPS)
            HealthConnectScopeStore.of(app).replace(configured)
            val reads = ModulePermissions.healthConnectReadPermissionsFor(configured)
            GrantedClient.value = client(false, reads)
            assertEquals(reads, HealthConnectPermissions.permissionsToRequest(app))
            assertTrue(HealthConnectPermissions.allGranted(app))
            GrantedClient.value = client(true, reads)
            assertFalse(HealthConnectPermissions.allGranted(app))
            GrantedClient.value = client(true, reads + ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ)
            assertTrue(HealthConnectPermissions.allGranted(app))
        } finally {
            instance.set(EncryptedPrefsHelper, previous)
        }
    }

    @Test @Config(sdk = [Build.VERSION_CODES.P, 36])
    fun backgroundReadsRequireBothFeatureAndGrant() {
        val grants = setOf(ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ)
        // Robolectric has no Health Connect provider; assert our permission/feature decision.
        assertTrue(HealthConnectPermissions.canReadInBackground(client(true), grants))
        assertFalse(HealthConnectPermissions.canReadInBackground(client(true), emptySet()))
        assertFalse(HealthConnectPermissions.canReadInBackground(client(false), grants))
    }

    @Test @Config(sdk = [27, Build.VERSION_CODES.P, Build.VERSION_CODES.TIRAMISU])
    fun unavailableProviderDoesNotCreateAClientOrReportGranted() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(HealthConnectPermissions.isAvailable(app))
        assertNull(HealthConnectPermissions.clientIfAvailable(app))
        assertFalse(HealthConnectPermissions.allGranted(app))
    }

    @Test @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 36])
    fun availableSystemProviderCanCreateAClient() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Availability requires the provider, not just API 34: https://developer.android.com/health-and-fitness/health-connect/get-started#check-availability
        installSystemProvider(app)
        assertTrue(HealthConnectPermissions.isAvailable(app))
        assertNotNull(HealthConnectPermissions.clientIfAvailable(app))
    }

    @Test @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 36])
    fun missingSystemProviderDoesNotCreateAClient() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadow.extract<ShadowContextImpl>(app.baseContext).setSystemService(Context.HEALTHCONNECT_SERVICE, null)
        assertFalse(HealthConnectPermissions.isAvailable(app))
        assertNull(HealthConnectPermissions.clientIfAvailable(app))
        assertFalse(HealthConnectPermissions.allGranted(app))
    }
}
