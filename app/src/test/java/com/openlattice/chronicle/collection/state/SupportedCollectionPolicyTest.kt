package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.BatteryPolicy
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.NetworkPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedCollectionPolicyTest {
    private fun minimalPlaySetting(
        enabled: Map<CollectionModuleId, CollectionModuleSetting>,
    ): AndroidDataCollectionSetting = AndroidDataCollectionSetting(
        modules = CollectionModuleId.entries
            .filter { it.active }
            .associateWith { CollectionModuleSetting(enabled = false) }
            .plus(CollectionModuleId.UPLOAD_TELEMETRY to CollectionModuleSetting(enabled = true))
            .plus(enabled),
    )

    @Test
    fun releaseDefaultsAreAccepted() {
        requireSupportedCollectionPolicies(
            minimalPlaySetting(
                enabled = mapOf(
                    CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = true),
                    CollectionModuleId.USER_IDENTIFICATION to CollectionModuleSetting(enabled = true),
                ),
            ),
        )
        requireSupportedCollectionPolicies(
            minimalPlaySetting(
                enabled = mapOf(
                    CollectionModuleId.CONNECTIVITY_STATE to CollectionModuleSetting(
                        enabled = true,
                        collectionCadence = CollectionCadence(intervalSeconds = 1_800),
                    ),
                ),
            ),
        )
    }

    @Test
    fun publicReleaseRejectsDisabledMandatoryUploadDiagnostics() {
        if (BuildConfig.DISTRIBUTION_CHANNEL !in setOf("PLAY", "AMAZON")) return
        val result = runCatching {
            requireSupportedCollectionPolicies(
                minimalPlaySetting(
                    enabled = mapOf(
                        CollectionModuleId.UPLOAD_TELEMETRY to CollectionModuleSetting(enabled = false),
                    ),
                ),
            )
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun playReleaseRejectsEveryModuleOutsideUsageBasicTelemetryAndUnlockIdentification() {
        if (BuildConfig.DISTRIBUTION_CHANNEL != "PLAY") return
        val supported = setOf(
            CollectionModuleId.USAGE_EVENTS,
            CollectionModuleId.IN_APP_ACTIVITY_CLASS,
            CollectionModuleId.DEVICE_LIFECYCLE,
            CollectionModuleId.USER_IDENTIFICATION,
            CollectionModuleId.UPLOAD_TELEMETRY,
            CollectionModuleId.BATTERY_TELEMETRY,
            CollectionModuleId.CONNECTIVITY_STATE,
            CollectionModuleId.APP_NETWORK_USAGE,
            CollectionModuleId.DEVICE_SETTINGS,
        )
        val forbidden = CollectionModuleId.entries.filter { it.active && it !in supported }

        forbidden.forEach { moduleId ->
            val result = runCatching {
                requireSupportedCollectionPolicies(
                    minimalPlaySetting(
                        enabled = mapOf(moduleId to CollectionModuleSetting(enabled = true)),
                    ),
                )
            }
            assertTrue(
                "Play accepted unsupported module ${moduleId.id}",
                result.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    @Test
    fun everyUnimplementedPolicyKnobIsRejectedBeforeConsent() {
        val unsupported = listOf(
            CollectionModuleSetting(enabled = true, collectionCadence = CollectionCadence(intervalSeconds = 60)),
            CollectionModuleSetting(
                enabled = true,
                collectionCadence = CollectionCadence(intervalSeconds = 900, jitterSeconds = 5),
            ),
            CollectionModuleSetting(
                enabled = true,
                uploadCadence = CollectionCadence(intervalSeconds = 120),
            ),
            CollectionModuleSetting(
                enabled = true,
                batteryPolicy = BatteryPolicy(minLevelPercent = 30),
            ),
            CollectionModuleSetting(
                enabled = true,
                networkPolicy = NetworkPolicy(requireUnmetered = true),
            ),
        )

        unsupported.forEach { setting ->
            val result = runCatching {
                requireSupportedCollectionPolicies(
                    minimalPlaySetting(
                        enabled = mapOf(CollectionModuleId.USAGE_EVENTS to setting),
                    ),
                )
            }
            check(result.exceptionOrNull() is IllegalArgumentException) {
                "Unsupported policy was accepted: $setting"
            }
        }
    }
}
