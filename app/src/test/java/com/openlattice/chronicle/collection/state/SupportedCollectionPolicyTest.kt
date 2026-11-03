package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    fun unsupportedEnabledModuleIsReportedAsAStudySetupProblem() {
        val unsupported = CollectionModuleId.entries.firstOrNull {
            it.active && !DistributionModulePolicy.supports(
                DistributionChannel.current(), it,
            )
        }
        assumeTrue(unsupported != null)
        val result = runCatching {
            requireSupportedCollectionPolicies(
                minimalPlaySetting(enabled = mapOf(unsupported!! to CollectionModuleSetting(enabled = true))),
            )
        }
        assertTrue(result.exceptionOrNull() is UnsupportedStudyModulesException)
    }

    @Test
    fun publicReleaseRejectsDisabledMandatoryUploadDiagnostics() {
        assumeTrue(DistributionChannel.current().minimalPublic)
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
    fun amazonReleaseRejectsEveryModuleOutsideUsageBasicTelemetryAndUnlockIdentification() {
        assumeTrue(DistributionChannel.current() == DistributionChannel.AMAZON)
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
    fun unsupportedCollectionCadenceIsRejectedBeforeConsent() {
        val unsupported = listOf(
            CollectionModuleSetting(enabled = true, collectionCadence = CollectionCadence(intervalSeconds = 60)),
            CollectionModuleSetting(
                enabled = true,
                collectionCadence = CollectionCadence(intervalSeconds = 900, jitterSeconds = 5),
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

    /** The study gives battery (a fixed-interval collector) a jittered cadence: refused before consent is asked. */
    @Test
    fun aJitteredCadenceOnAnIntervalCollectorIsRejectedBeforeConsent() {
        val result = runCatching {
            requireSupportedCollectionPolicies(
                minimalPlaySetting(
                    enabled = mapOf(CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(
                        enabled = true,
                        collectionCadence = CollectionCadence(intervalSeconds = 1_800, jitterSeconds = 30),
                    )),
                ),
            )
        }
        assertEquals("battery_telemetry uses unsupported collection-cadence jitter", result.exceptionOrNull()?.message)
    }

    /** A recovered pending enrollment lists usage as "not on this device": only hardware sensors can be, so it is refused. */
    @Test
    fun onlyHardwareSensorsCanBeUnavailableInAnEnrollment() {
        val settings = minimalPlaySetting(enabled = mapOf(CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = true)))
        val result = runCatching {
            validateEnrollmentModulePartition(settings, settings.effectiveEnabledModuleIds() - CollectionModuleId.USAGE_EVENTS,
                emptySet(), setOf(CollectionModuleId.USAGE_EVENTS))
        }
        assertEquals("Only per-sensor hardware modules may be unavailable", result.exceptionOrNull()?.message)
    }
}
