package com.openlattice.chronicle.collection.activity

import android.content.Context
import com.openlattice.chronicle.collection.ActivityTransitionType
import com.openlattice.chronicle.collection.AndroidActivityRecognitionEvent
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.DetectedActivityType
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.storage.ActivityRecognitionSampleEntry
import java.time.OffsetDateTime

private const val QUEUE_DEPTH_UNAVAILABLE = -1

/**
 * The `activity_recognition` collection module (Play Services Activity Recognition / Activity
 * Transition API). **Push-style**: the device delivers activity transitions to an app-side
 * receiver that persists rows, so this module does no work in [poll]; it is the registry citizen
 * and the home of the row → wire-DTO mapping. BEHAVIORAL_METADATA-class (default OFF).
 *
 * @param queueDepth reads the pending-row count for diagnostics (the app wires this to the sink).
 */
public class ActivityRecognitionCollectionModule(
    private val queueDepth: () -> Int,
) : DataCollectionModule {

    override val id: CollectionModuleId = CollectionModuleId.ACTIVITY_RECOGNITION
    override val privacyClass: CollectionPrivacyClass = id.privacyClass

    override fun status(): CollectionModuleStatus = CollectionModuleStatus.IDLE

    override fun diagnostics(): CollectionModuleDiagnostics = CollectionModuleDiagnostics(
        moduleId = id,
        privacyClass = privacyClass,
        lastRunEpochMs = null,
        lastResult = ModuleResult.Skipped("push-captured").label,
        itemsCollected = 0,
        queueDepth = runCatching { queueDepth() }.getOrDefault(QUEUE_DEPTH_UNAVAILABLE),
        lastError = null,
        redactedParticipantRef = null,
    )

    override fun poll(context: Context, window: CollectionWindow): ModuleResult =
        ModuleResult.Skipped("activity_recognition is push-style (Play Services Activity Transition API)")

    override fun start(context: Context): ModuleResult =
        ModuleResult.Skipped("activity registration is driven by the app capture controller")

    override fun stop(context: Context): ModuleResult =
        ModuleResult.Skipped("activity registration is driven by the app capture controller")

    override fun flush(context: Context): ModuleResult = ModuleResult.Skipped("activity_recognition buffers nothing")
}

/**
 * Converts a stored [ActivityRecognitionSampleEntry] row into the
 * [AndroidActivityRecognitionEvent] wire DTO. Throws on an unparseable timestamp or unknown enum
 * name; the upload path catches this per row so one corrupt row never aborts a batch.
 */
public fun ActivityRecognitionSampleEntry.toAndroidActivityRecognitionEvent(): AndroidActivityRecognitionEvent =
    AndroidActivityRecognitionEvent(
        id = id,
        timestamp = OffsetDateTime.parse(timestamp),
        timezone = timezone,
        activityType = DetectedActivityType.valueOf(activityType),
        confidence = confidence,
        transitionType = transitionType?.let { ActivityTransitionType.valueOf(it) },
    )
