package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.SensorCollectionModules

/**
 * Participant-facing consent copy for the consent-gated collection modules, mirroring the
 * web study form's `COLLECTION_MODULES` (chronicle-web `study-constants.ts`) so researchers
 * and participants see the same plain-language wording.
 *
 * Each module carries a structured [ModuleTemplate] — label, *what it collects*, *what it
 * does NOT collect*, and privacy class — which drives the per-module enrollment orientation
 * wizard ([CollectionOrientationActivity]) and the Data Sharing management surface. The copy
 * is app-canonical and pre-specified (per-module consent design §8); the study config only
 * enables a module and marks it required/optional — it never authors this text.
 *
 * Copy approved by the project owner (docs/COLLECTION-LOOP-CLOSURE-DESIGN.md Appendix B;
 * extended for per-module consent design §8). `{studyTitle}` is rendered generically as
 * "this study" (the device persists only `studyId`, not the title). Final consent wording
 * remains the study/IRB's responsibility.
 */
object CollectionConsentCopy {

    const val TITLE = "Review what this study collects"

    const val INTRO =
        "This study would like to collect the following on this device. Collection does not " +
            "start until you agree. You can review this at any time; if you have questions, " +
            "contact your study team."

    const val FOOTER = "Agreeing records the date and time of your decision with the study."

    /** One module's participant-facing template: what it does, what it doesn't, and its class. */
    data class ModuleTemplate(
        val label: String,
        val whatItCollects: List<String>,
        val whatItDoesNotCollect: List<String>,
        val privacyClass: String,
        /**
         * Optional explicit warnings shown prominently (separate from the collect/doesn't-collect
         * lists). Use for risks the participant must actively avoid — e.g. shared-hardware data
         * bleed on Health Connect. Empty for most modules.
         */
        val caveats: List<String> = emptyList(),
    )

    private val TEMPLATES: Map<CollectionModuleId, ModuleTemplate> = mapOf(
        CollectionModuleId.USAGE_EVENTS to ModuleTemplate(
            label = "App Usage Events",
            whatItCollects = listOf(
                "Which apps you open and close, and when",
                "How long apps stay in the foreground",
                "Screen, keyguard, and Android runtime startup/shutdown events in the same usage timeline",
            ),
            whatItDoesNotCollect = listOf(
                "The content inside apps — messages, photos, or videos",
                "What you type",
                "Web pages or search terms",
            ),
            privacyClass = "Behavioral metadata",
        ),
        CollectionModuleId.DEVICE_LIFECYCLE to ModuleTemplate(
            label = "Device Lifecycle",
            whatItCollects = listOf(
                "Supplemental battery, charging, power-saving, network, and low-memory state changes",
            ),
            whatItDoesNotCollect = listOf(
                "Anything you do on the screen",
                "App contents or personal data",
            ),
            privacyClass = "Device-state metadata",
        ),
        CollectionModuleId.USER_IDENTIFICATION to ModuleTemplate(
            label = "Unlock User Identification",
            whatItCollects = listOf(
                "After device unlock, whether the person using the device is the study participant or someone else",
                "When that selection was made, so later app-usage events can carry the same selection",
            ),
            whatItDoesNotCollect = listOf(
                "A person's name, face, fingerprint, voice, or other biometric identifier",
                "The device PIN, password, or unlock method",
                "Notification contents, messages, photos, or screen contents",
            ),
            privacyClass = "Participant-provided behavioral label",
        ),
        CollectionModuleId.BATTERY_TELEMETRY to ModuleTemplate(
            label = "Battery Telemetry",
            whatItCollects = listOf(
                "Battery level, charging state, and power events",
            ),
            whatItDoesNotCollect = listOf(
                "Anything you do on the device",
                "App contents or personal data",
            ),
            privacyClass = "Device-state metadata",
        ),
        CollectionModuleId.IN_APP_ACTIVITY_CLASS to ModuleTemplate(
            label = "In-App Activity",
            whatItCollects = listOf(
                "Which screen within an app is open (the app's screen/activity name), alongside app usage",
                "The same timing and detail as the app-usage log this adds to",
            ),
            whatItDoesNotCollect = listOf(
                "The text or content shown on the screen",
                "What you type, search, or message",
                "Screenshots or screen contents",
            ),
            privacyClass = "Behavioral metadata",
        ),
        CollectionModuleId.CONNECTIVITY_STATE to ModuleTemplate(
            label = "Connectivity State",
            whatItCollects = listOf(
                "Your connection type (Wi-Fi, cellular, or none) and when it changes",
                "Whether the connection is metered or validated",
            ),
            whatItDoesNotCollect = listOf(
                "Wi-Fi network names, or the sites and apps you connect to",
                "Your location",
                "Anything you type, read, or message",
            ),
            privacyClass = "Device-state metadata",
        ),
        CollectionModuleId.DEVICE_SETTINGS to ModuleTemplate(
            label = "Device Settings",
            whatItCollects = listOf(
                "A snapshot of device settings — dark mode, font size, Do Not Disturb, battery saver, auto-rotate, and similar",
                "Free storage and whether location services are turned on",
            ),
            whatItDoesNotCollect = listOf(
                "Anything you do on the device or any app contents",
                "Your location coordinates",
                "Anything you type, read, or message",
            ),
            privacyClass = "Device-state metadata",
        ),
    ) + if (BuildConfig.HAS_APP_NETWORK_USAGE) {
        appNetworkUsageTemplates()
    } else {
        emptyMap()
    } + if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
        restrictedTemplates() + sensorTemplates()
    } else {
        emptyMap()
    } + if (BuildConfig.HAS_HEALTH_CONNECT) {
        healthConnectTemplates()
    } else {
        emptyMap()
    }

    /** App-network copy is excluded from Play/Amazon and removed from their optimized artifacts. */
    private fun appNetworkUsageTemplates(): Map<CollectionModuleId, ModuleTemplate> = mapOf(
        CollectionModuleId.APP_NETWORK_USAGE to ModuleTemplate(
            label = "App Network Usage",
            whatItCollects = listOf(
                "How much data each app sends and receives (byte counts)",
                "Whether it was over Wi-Fi or cellular, and the time period",
            ),
            whatItDoesNotCollect = listOf(
                "The contents of any data, the sites, or who you connect to",
                "Your location",
                "Anything you type, read, or message",
            ),
            privacyClass = "Behavioral metadata",
        ),
    )

    /** Restricted research copy is unreachable and R8-removed from the Play artifact. */
    private fun restrictedTemplates(): Map<CollectionModuleId, ModuleTemplate> = mapOf(
        CollectionModuleId.INTERACTION_EVENTS to ModuleTemplate(
            label = "Interaction Events",
            whatItCollects = listOf(
                "Roughly where on the screen you tap and scroll (a coarse grid region)",
                "The kind of element you interact with, and which app is open",
            ),
            whatItDoesNotCollect = listOf(
                "The text or content of what you tap or read",
                "What you type, search, or message",
                "Exact tap locations, screenshots, or screen contents",
            ),
            privacyClass = "Interaction metadata",
        ),
        CollectionModuleId.AUDIO_ACTIVITY to ModuleTemplate(
            label = "Audio Status",
            whatItCollects = listOf(
                "Which app is playing audio and whether audio is playing",
                "Headphone / Bluetooth / speaker connection and the media volume level",
                "Whether a phone call is in progress (not the call, the audio, or who)",
            ),
            whatItDoesNotCollect = listOf(
                "The microphone or any sound around you",
                "The audio itself or anything that is said",
                "What you type, search, or message",
            ),
            privacyClass = "Behavioral metadata",
        ),
        CollectionModuleId.AUDIO_CONTENT to ModuleTemplate(
            label = "Audio Metadata",
            whatItCollects = listOf(
                "The title, artist, and album of media you play",
                "Playback position and duration",
            ),
            whatItDoesNotCollect = listOf(
                "The microphone or any recording",
                "The audio itself",
                "Anything you type or message",
            ),
            privacyClass = "Media content",
        ),
        CollectionModuleId.NOTIFICATION_ACTIVITY to ModuleTemplate(
            label = "Notification Activity",
            whatItCollects = listOf(
                "How many notifications you receive and from which app",
                "The notification category (message, call, alarm) and when it arrives",
            ),
            whatItDoesNotCollect = listOf(
                "The text or content of any notification",
                "Who sent it or what it says",
                "Messages, names, or anything you type",
            ),
            privacyClass = "Behavioral metadata",
        ),
        CollectionModuleId.SLEEP to ModuleTemplate(
            label = "Sleep",
            whatItCollects = listOf(
                "When you appear to be asleep or awake (sleep segments from Android's Sleep API)",
                "A confidence score for each segment",
            ),
            whatItDoesNotCollect = listOf(
                "Audio, the microphone, or the camera",
                "Your location",
                "Anything you type, read, or message",
            ),
            privacyClass = "Health metrics",
        ),
        CollectionModuleId.ACTIVITY_RECOGNITION to ModuleTemplate(
            label = "Physical Activity",
            whatItCollects = listOf(
                "Your general activity type — still, walking, running, cycling, or in a vehicle",
                "A confidence score for each activity",
            ),
            whatItDoesNotCollect = listOf(
                "Your location or GPS",
                "Audio, the microphone, or the camera",
                "Anything you type or message",
            ),
            privacyClass = "Behavioral metadata",
        ),
    )

    /** Health Connect copy is unreachable and R8-removed when the capability is absent. */
    private fun healthConnectTemplates(): Map<CollectionModuleId, ModuleTemplate> = mapOf(
        CollectionModuleId.HEALTH_CONNECT to ModuleTemplate(
            label = "Health Connect",
            whatItCollects = listOf(
                "The Health Connect record types listed for the active study",
                "When each approved record was recorded",
            ),
            whatItDoesNotCollect = listOf(
                "Any Health Connect record type not listed for the active study or not granted in Android",
                "Audio, the microphone, or the camera",
                "Anything you type or message",
            ),
            privacyClass = "Health metrics",
            caveats = listOf(
                "Only turn this on for a device whose Health Connect data is yours alone. " +
                    "Health Connect collects whatever your apps and devices write into it — so if " +
                    "you share a smart scale, watch, fitness band, or health app with someone else, " +
                    "their measurements can be recorded as if they were yours. If your health " +
                    "devices are shared, leave this off.",
            ),
        ),
    )

    /** What every per-sensor module collects, keyed by sensor — drives its Data Sharing row copy. */
    private fun sensorCollects(): Map<AndroidSensorType, Pair<String, String>> = mapOf(
        AndroidSensorType.accelerometer to ("Accelerometer" to "Device motion and acceleration along three axes"),
        AndroidSensorType.gyroscope to ("Gyroscope" to "Rotation and angular velocity"),
        AndroidSensorType.magnetometer to ("Magnetometer" to "Magnetic field and compass heading"),
        AndroidSensorType.gravity to ("Gravity Sensor" to "The direction and magnitude of gravity"),
        AndroidSensorType.linearAcceleration to ("Linear Acceleration" to "Acceleration with gravity removed"),
        AndroidSensorType.rotationVector to ("Rotation Vector" to "The device's orientation in space"),
        AndroidSensorType.stepCounter to ("Step Counter" to "The number of steps taken"),
        AndroidSensorType.light to ("Light Sensor" to "The ambient light level around the device"),
        AndroidSensorType.proximity to ("Proximity Sensor" to "Whether something is near the screen"),
        AndroidSensorType.significantMotion to ("Significant Motion" to "When the device undergoes significant motion"),
        AndroidSensorType.tiltDetector to ("Tilt Detector" to "When the device is tilted"),
        AndroidSensorType.screenOrientation to ("Screen Orientation" to "The screen orientation (portrait or landscape)"),
        // The Samsung vendor sensors (grip_wifi, samsung_motion) are retired to decode-only
        // aliases — never offered or consented to — so they carry no participant-facing copy.
    )

    /** What no sensor collects — shared across every per-sensor template. */
    private fun sensorDoesNotCollect(): List<String> = listOf(
        "Your location or GPS",
        "Audio, the microphone, or the camera",
        "App contents or anything you type",
    )

    /**
     * Builds one [ModuleTemplate] per sensor module (per-sensor consent redesign, 2026-06-11):
     * each sensor is presented and consented to individually, exactly like a usage module.
     * Declared as functions (not object vals) so the eager [TEMPLATES] map can call this during
     * object init without a forward-reference to an as-yet-uninitialized val.
     */
    private fun sensorTemplates(): Map<CollectionModuleId, ModuleTemplate> =
        sensorCollects().entries.associate { (sensorType, copy) ->
            val (label, collectsLine) = copy
            SensorCollectionModules.moduleFor(sensorType) to ModuleTemplate(
                label = label,
                whatItCollects = listOf(collectsLine),
                whatItDoesNotCollect = sensorDoesNotCollect(),
                privacyClass = "Physical telemetry",
            )
        }

    /** The structured template for [moduleId], synthesizing a safe fallback for an unmapped id. */
    fun template(moduleId: CollectionModuleId): ModuleTemplate =
        TEMPLATES[moduleId] ?: ModuleTemplate(
            label = moduleLabel(moduleId),
            whatItCollects = emptyList(),
            whatItDoesNotCollect = emptyList(),
            privacyClass = privacyLabel(moduleId),
        )

    /**
     * Template used when the participant is making a consent decision. Health Connect is
     * intentionally stricter than the general read-only template: the server must supply a
     * non-empty approved scope and the screen names every approved record family exactly.
     */
    fun consentTemplate(
        moduleId: CollectionModuleId,
        healthConnectRecordTypes: Set<HealthConnectRecordType>,
    ): ModuleTemplate {
        if (!BuildConfig.HAS_HEALTH_CONNECT) return template(moduleId)
        if (moduleId != CollectionModuleId.HEALTH_CONNECT) return template(moduleId)
        require(healthConnectRecordTypes.isNotEmpty()) {
            "Health Connect consent requires at least one study-approved record type"
        }
        val approvedRecordTypes = HealthConnectRecordType.entries
            .filter(healthConnectRecordTypes::contains)
            .map(::healthConnectRecordTypeLabel)
        return template(moduleId).copy(
            whatItCollects = approvedRecordTypes + "When each approved record was recorded",
            whatItDoesNotCollect = listOf(
                "Any Health Connect record type not listed above or not granted in Android",
                "Audio, the microphone, or the camera",
                "Anything you type or message",
            ),
        )
    }

    /** Renders the bullet list of [modules] for a combined consent view (legacy ack screen). */
    fun bullets(modules: Set<CollectionModuleId>): String =
        modules.joinToString("\n\n") { id ->
            val copy = template(id)
            if (copy.whatItCollects.isEmpty()) {
                "• ${copy.label} (${copy.privacyClass})"
            } else {
                "• ${copy.label} — ${copy.whatItCollects.joinToString("; ")} (${copy.privacyClass})"
            }
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

    private fun healthConnectRecordTypeLabel(type: HealthConnectRecordType): String = when (type) {
        HealthConnectRecordType.STEPS -> "Steps"
        HealthConnectRecordType.DISTANCE -> "Distance"
        HealthConnectRecordType.HEART_RATE -> "Heart rate"
        HealthConnectRecordType.TOTAL_CALORIES_BURNED -> "Total calories burned"
        HealthConnectRecordType.ACTIVE_CALORIES_BURNED -> "Active calories burned"
        HealthConnectRecordType.FLOORS_CLIMBED -> "Floors climbed"
        HealthConnectRecordType.RESTING_HEART_RATE -> "Resting heart rate"
        HealthConnectRecordType.OXYGEN_SATURATION -> "Oxygen saturation"
        HealthConnectRecordType.RESPIRATORY_RATE -> "Respiratory rate"
        HealthConnectRecordType.SLEEP -> "Sleep sessions and stages"
        HealthConnectRecordType.EXERCISE -> "Exercise sessions"
        HealthConnectRecordType.HEART_RATE_VARIABILITY -> "Heart-rate variability"
        HealthConnectRecordType.BODY_TEMPERATURE -> "Body temperature"
        HealthConnectRecordType.SKIN_TEMPERATURE -> "Skin temperature"
    }
}
