package com.openlattice.chronicle.collection.state

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType

/**
 * Locale overrides for the per-module consent copy. [CollectionConsentCopy] stays the tested
 * English source of truth; a translation may supply, per module id, the resources
 * `consent_<module>_label`, `consent_<module>_privacy_class` (strings) and
 * `consent_<module>_collects`, `consent_<module>_not_collects`, `consent_<module>_caveats`
 * (string-arrays). Anything not supplied falls back to the English template, so a partial
 * translation never blanks a screen. Health Connect's record-type bullets are generated from the
 * study scope through the shared `hc_record_<type>` strings; a translation may also supply
 * `consent_health_connect_collects_trailer` (string, the bullet after the record types) and
 * `consent_health_connect_not_collects` (string-array).
 */
private fun Context.consentString(module: CollectionModuleId, suffix: String): String? {
    val id = resources.getIdentifier("consent_${module.id.replace('-', '_')}_$suffix", "string", packageName)
    return if (id == 0) null else getString(id)
}

private fun Context.consentArray(module: CollectionModuleId, suffix: String): List<String>? {
    val id = resources.getIdentifier("consent_${module.id.replace('-', '_')}_$suffix", "array", packageName)
    return if (id == 0) null else resources.getStringArray(id).toList()
}

private fun CollectionConsentCopy.ModuleTemplate.localized(
    context: Context,
    module: CollectionModuleId,
    overrideLists: Boolean,
): CollectionConsentCopy.ModuleTemplate = copy(
    label = context.consentString(module, "label") ?: label,
    privacyClass = context.consentString(module, "privacy_class") ?: privacyClass,
    whatItCollects = (if (overrideLists) context.consentArray(module, "collects") else null) ?: whatItCollects,
    whatItDoesNotCollect =
        (if (overrideLists) context.consentArray(module, "not_collects") else null) ?: whatItDoesNotCollect,
    caveats = (if (overrideLists) context.consentArray(module, "caveats") else null) ?: caveats,
)

fun CollectionConsentCopy.localizedLabel(context: Context, module: CollectionModuleId): String =
    context.consentString(module, "label") ?: template(module).label

fun CollectionConsentCopy.localizedTemplate(
    context: Context,
    module: CollectionModuleId,
): CollectionConsentCopy.ModuleTemplate = template(module).localized(context, module, overrideLists = true)

fun CollectionConsentCopy.localizedConsentTemplate(
    context: Context,
    module: CollectionModuleId,
    healthConnectRecordTypes: Set<HealthConnectRecordType>,
): CollectionConsentCopy.ModuleTemplate {
    val english = consentTemplate(module, healthConnectRecordTypes)
    if (module != CollectionModuleId.HEALTH_CONNECT) return english.localized(context, module, overrideLists = true)
    val recordLabels = HealthConnectRecordType.entries
        .filter(healthConnectRecordTypes::contains)
        .map { context.healthConnectRecordLabel(it) }
    val trailer = context.consentString(module, "collects_trailer") ?: english.whatItCollects.last()
    return english.localized(context, module, overrideLists = false).copy(
        whatItCollects = recordLabels + trailer,
        whatItDoesNotCollect = context.consentArray(module, "not_collects") ?: english.whatItDoesNotCollect,
    )
}

/** `hc_record_steps` = "steps"; the consent bullets are sentence-cased in the current locale. */
private fun Context.healthConnectRecordLabel(type: HealthConnectRecordType): String {
    val id = resources.getIdentifier("hc_record_${type.name.lowercase()}", "string", packageName)
    require(id != 0) { "Missing hc_record string for $type" }
    return getString(id).replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
    }
}
