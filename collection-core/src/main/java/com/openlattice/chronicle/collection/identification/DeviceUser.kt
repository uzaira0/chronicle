package com.openlattice.chronicle.collection.identification

/**
 * The device-user choices. [stored] is the English value written to the user queue and `current_user`,
 * uploaded with usage events and exported, the same on every phone locale. Screens show the app's
 * translated label for the choice instead, never [stored].
 *
 * [legacy] is the value older releases wrote ("Target child" / "Other"). Every read goes
 * through [fromStored], so a phone that still holds a legacy value shows and uploads the current one.
 * Rows already queued keep the value they were captured with.
 */
public enum class DeviceUser(public val stored: String, private val legacy: String? = null) {
    STUDY_PARTICIPANT("Study participant", legacy = "Target child"),
    SOMEONE_ELSE("Someone else", legacy = "Other"),
    NOT_SET("Not set"),
    ;

    public companion object {
        /** The choice [value] names, current or legacy; null for a value no release wrote. */
        @JvmStatic
        public fun fromStored(value: String?): DeviceUser? =
            entries.firstOrNull { value == it.stored || (it.legacy != null && value == it.legacy) }

        /** [value] in its current spelling; an unknown value is returned unchanged. */
        @JvmStatic
        public fun current(value: String): String = fromStored(value)?.stored ?: value
    }
}
