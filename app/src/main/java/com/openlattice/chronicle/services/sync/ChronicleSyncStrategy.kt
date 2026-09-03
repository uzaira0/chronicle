package com.openlattice.chronicle.services.sync

enum class ChronicleSyncStrategy(val configValue: String) {
    SPLIT_PERIODIC("split_periodic"),
    COORDINATED_COLLECT_THEN_UPLOAD("coordinated_collect_then_upload"),
    COORDINATED_UPLOAD_THEN_COLLECT("coordinated_upload_then_collect");

    companion object {
        val DEFAULT = COORDINATED_COLLECT_THEN_UPLOAD

        fun fromConfigValue(value: String?): ChronicleSyncStrategy {
            val normalized = value?.trim()?.lowercase()?.replace('-', '_')
            return entries.firstOrNull {
                it.configValue == normalized || it.name.lowercase() == normalized
            } ?: DEFAULT
        }
    }
}
