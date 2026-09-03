package com.openlattice.chronicle.compat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.CollectionCapability
import com.openlattice.chronicle.collection.capability.CollectionCapabilityResolver
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimePermissionBreakpointTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context
        get() = instrumentation.targetContext

    @Test
    fun platformPermissionStateMatchesCapabilityState() {
        val arguments = InstrumentationRegistry.getArguments()
        val expectedStateArgument = arguments.getString("expectedPermissionState")
        val expectedDistributionArgument = arguments.getString("expectedDistribution")
        assumeTrue(
            "permission-breakpoint proof requires expectedPermissionState runner argument",
            !expectedStateArgument.isNullOrBlank(),
        )
        assumeTrue(
            "permission-breakpoint proof requires expectedDistribution runner argument",
            !expectedDistributionArgument.isNullOrBlank(),
        )
        val expectedState = requireNotNull(expectedStateArgument)
        require(expectedState in setOf("legacy", "denied", "granted")) {
            "expectedPermissionState must be legacy, denied, or granted"
        }
        val expectedDistribution = requireNotNull(expectedDistributionArgument)
        assertEquals(expectedDistribution, DistributionChannel.current().name)

        val requested = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()
        assertTrue(ModulePermissions.ACTIVITY_RECOGNITION in requested)

        val runtime = ModulePermissions.runtimePermissionsFor(
            listOf(CollectionModuleId.ACTIVITY_RECOGNITION),
            Build.VERSION.SDK_INT,
        )
        val activityGranted = ContextCompat.checkSelfPermission(
            context,
            ModulePermissions.ACTIVITY_RECOGNITION,
        ) == PackageManager.PERMISSION_GRANTED

        when (expectedState) {
            "legacy" -> {
                assertTrue(Build.VERSION.SDK_INT < 29)
                assertTrue(runtime.isEmpty())
            }
            "denied" -> {
                assertTrue(Build.VERSION.SDK_INT >= 29)
                assertEquals(setOf(ModulePermissions.ACTIVITY_RECOGNITION), runtime)
                assertTrue(!activityGranted)
            }
            "granted" -> {
                assertTrue(Build.VERSION.SDK_INT >= 29)
                assertEquals(setOf(ModulePermissions.ACTIVITY_RECOGNITION), runtime)
                assertTrue(activityGranted)
            }
            else -> error("Unexpected permission state: $expectedState")
        }

        val capability = CollectionCapabilityResolver.resolve(
            CollectionModuleId.ACTIVITY_RECOGNITION,
            CollectionCapabilityResolver.snapshot(context),
        )
        when {
            DistributionChannel.current() == DistributionChannel.AMAZON ->
                assertTrue(capability is CollectionCapability.ServiceUnavailable)
            capability is CollectionCapability.ServiceUnavailable ->
                assertTrue(capability.message.contains("Google Play Services"))
            expectedState == "denied" ->
                assertTrue(capability is CollectionCapability.PermissionRequired)
            else -> assertEquals(CollectionCapability.Ready, capability)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue(Manifest.permission.POST_NOTIFICATIONS in requested)
            val notificationsGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            assertEquals(expectedState == "granted", notificationsGranted)
        }
    }
}
