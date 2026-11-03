package com.openlattice.chronicle.collection.state

import android.content.Context
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.DistributionCollectionContributions
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import java.util.Locale

/**
 * Participant-facing consent copy for the consent-gated collection modules, mirroring the
 * web study form's `COLLECTION_MODULES` (chronicle-web `study-constants.ts`) so researchers
 * and participants see the same plain-language wording.
 *
 * The wording lives only in `res/values/consent_copy.xml` (modules every artifact supports) and
 * `googleServices/res/values/consent_copy.xml` (restricted modules); translations override those
 * keys per locale. Each module's [ModuleTemplate] drives the per-module enrollment orientation
 * wizard ([CollectionOrientationActivity]) and the Data Sharing management surface. The study
 * config only enables a module and marks it required/optional; it never authors this text.
 * Final consent wording remains the study/IRB's responsibility.
 */
object CollectionConsentCopy {

    /** One module's participant-facing template: what it does, what it doesn't, and its class. */
    data class ModuleTemplate(
        val label: String,
        val whatItCollects: List<String>,
        val whatItDoesNotCollect: List<String>,
        val privacyClass: String,
        /**
         * Optional explicit warnings shown prominently (separate from the collect/doesn't-collect
         * lists), e.g. shared-hardware data bleed on Health Connect. Empty for most modules.
         */
        val caveats: List<String> = emptyList(),
    )

    /** The resources holding one module's copy. */
    class Resources(
        @param:StringRes val label: Int,
        @param:StringRes val privacyClass: Int,
        @param:ArrayRes val collects: Int,
        @param:ArrayRes val notCollects: Int,
        @param:ArrayRes val caveats: Int? = null,
        /** Health Connect only: the bullet after the study-approved record types. */
        @param:StringRes val approvedScopeTrailer: Int? = null,
    )

    private val SHARED: Map<CollectionModuleId, Resources> = mapOf(
        CollectionModuleId.USAGE_EVENTS to Resources(
            R.string.consent_usage_events_label,
            R.string.consent_usage_events_privacy_class,
            R.array.consent_usage_events_collects,
            R.array.consent_usage_events_not_collects,
        ),
        CollectionModuleId.DEVICE_LIFECYCLE to Resources(
            R.string.consent_device_lifecycle_label,
            R.string.consent_device_lifecycle_privacy_class,
            R.array.consent_device_lifecycle_collects,
            R.array.consent_device_lifecycle_not_collects,
        ),
        CollectionModuleId.USER_IDENTIFICATION to Resources(
            R.string.consent_user_identification_label,
            R.string.consent_user_identification_privacy_class,
            R.array.consent_user_identification_collects,
            R.array.consent_user_identification_not_collects,
        ),
        CollectionModuleId.BATTERY_TELEMETRY to Resources(
            R.string.consent_battery_telemetry_label,
            R.string.consent_battery_telemetry_privacy_class,
            R.array.consent_battery_telemetry_collects,
            R.array.consent_battery_telemetry_not_collects,
        ),
        CollectionModuleId.IN_APP_ACTIVITY_CLASS to Resources(
            R.string.consent_in_app_activity_class_label,
            R.string.consent_in_app_activity_class_privacy_class,
            R.array.consent_in_app_activity_class_collects,
            R.array.consent_in_app_activity_class_not_collects,
        ),
        CollectionModuleId.CONNECTIVITY_STATE to Resources(
            R.string.consent_connectivity_state_label,
            R.string.consent_connectivity_state_privacy_class,
            R.array.consent_connectivity_state_collects,
            R.array.consent_connectivity_state_not_collects,
        ),
        CollectionModuleId.DEVICE_SETTINGS to Resources(
            R.string.consent_device_settings_label,
            R.string.consent_device_settings_privacy_class,
            R.array.consent_device_settings_collects,
            R.array.consent_device_settings_not_collects,
        ),
    )

    /**
     * Names of the enabled study functions that are not a participant choice (the study disclosure
     * lists them). They have no collect lists, but the plan and Data Sharing screens still name them.
     */
    private val OPERATIONAL_NAMES: Map<CollectionModuleId, Pair<Int, Int>> = mapOf(
        CollectionModuleId.UPLOAD_TELEMETRY to
            (R.string.consent_upload_telemetry_label to R.string.consent_upload_telemetry_privacy_class),
        CollectionModuleId.SENSOR_AVAILABILITY to
            (R.string.consent_sensor_availability_label to R.string.consent_sensor_availability_privacy_class),
    )

    /** Copy for [moduleId] when this artifact supports it; the restricted modules come from the flavor. */
    private fun resourcesFor(moduleId: CollectionModuleId): Resources? =
        (SHARED[moduleId] ?: DistributionCollectionContributions.consentResources[moduleId])
            ?.takeIf { DistributionModulePolicy.supports(DistributionChannel.current(), moduleId) }

    /** The structured template for [moduleId], synthesizing a safe fallback for an unmapped id. */
    fun template(context: Context, moduleId: CollectionModuleId): ModuleTemplate {
        val res = resourcesFor(moduleId) ?: return OPERATIONAL_NAMES[moduleId]
            ?.let { (label, privacyClass) ->
                ModuleTemplate(context.getString(label), emptyList(), emptyList(), context.getString(privacyClass))
            }
            ?: ModuleTemplate(
                label = moduleLabel(moduleId),
                whatItCollects = emptyList(),
                whatItDoesNotCollect = emptyList(),
                privacyClass = privacyLabel(moduleId),
            )
        val strings = context.resources
        return ModuleTemplate(
            label = strings.getString(res.label),
            whatItCollects = strings.getStringArray(res.collects).toList(),
            whatItDoesNotCollect = strings.getStringArray(res.notCollects).toList(),
            privacyClass = strings.getString(res.privacyClass),
            caveats = res.caveats?.let { strings.getStringArray(it).toList() }.orEmpty(),
        )
    }

    fun label(context: Context, moduleId: CollectionModuleId): String = template(context, moduleId).label

    /** False when [template] would show the id-derived English fallback for [moduleId]. */
    internal fun hasCuratedNames(moduleId: CollectionModuleId): Boolean = resourcesFor(moduleId) != null || moduleId in OPERATIONAL_NAMES

    /**
     * Template used when the participant is making a consent decision. Health Connect is
     * intentionally stricter than the general template: the server must supply a non-empty
     * approved scope and the screen names every approved record family exactly.
     */
    fun consentTemplate(
        context: Context,
        moduleId: CollectionModuleId,
        healthConnectRecordTypes: Set<HealthConnectRecordType>,
    ): ModuleTemplate {
        val template = template(context, moduleId)
        val trailer = resourcesFor(moduleId)?.approvedScopeTrailer ?: return template
        require(healthConnectRecordTypes.isNotEmpty()) {
            "Health Connect consent requires at least one study-approved record type"
        }
        val approvedRecordTypes = HealthConnectRecordType.entries
            .filter(healthConnectRecordTypes::contains)
            .map { type ->
                context.getString(healthConnectRecordLabel(type)).replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            }
        // "What it does NOT collect" is the template's list unchanged, so one resource
        // (consent_health_connect_not_collects) serves both the consent and Data Sharing views.
        return template.copy(
            whatItCollects = approvedRecordTypes + context.getString(trailer),
        )
    }

    /** The lower-case display name of one Health Connect record family. */
    @StringRes
    fun healthConnectRecordLabel(type: HealthConnectRecordType): Int = when (type) {
        HealthConnectRecordType.STEPS -> R.string.hc_record_steps
        HealthConnectRecordType.DISTANCE -> R.string.hc_record_distance
        HealthConnectRecordType.HEART_RATE -> R.string.hc_record_heart_rate
        HealthConnectRecordType.TOTAL_CALORIES_BURNED -> R.string.hc_record_total_calories_burned
        HealthConnectRecordType.ACTIVE_CALORIES_BURNED -> R.string.hc_record_active_calories_burned
        HealthConnectRecordType.FLOORS_CLIMBED -> R.string.hc_record_floors_climbed
        HealthConnectRecordType.RESTING_HEART_RATE -> R.string.hc_record_resting_heart_rate
        HealthConnectRecordType.OXYGEN_SATURATION -> R.string.hc_record_oxygen_saturation
        HealthConnectRecordType.RESPIRATORY_RATE -> R.string.hc_record_respiratory_rate
        HealthConnectRecordType.SLEEP -> R.string.hc_record_sleep
        HealthConnectRecordType.EXERCISE -> R.string.hc_record_exercise
        HealthConnectRecordType.HEART_RATE_VARIABILITY -> R.string.hc_record_heart_rate_variability
        HealthConnectRecordType.BODY_TEMPERATURE -> R.string.hc_record_body_temperature
        HealthConnectRecordType.SKIN_TEMPERATURE -> R.string.hc_record_skin_temperature
    }

    /** "battery_telemetry" -> "Battery Telemetry" (fallback label for an unmapped id). */
    private fun moduleLabel(id: CollectionModuleId): String =
        id.id.split('_').joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

    private fun privacyLabel(id: CollectionModuleId): String =
        id.privacyClass.name.split('_').joinToString(" ") { part ->
            part.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
}
