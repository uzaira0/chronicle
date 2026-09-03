package com.openlattice.chronicle.collection.lifecycle

import android.content.Context
import com.openlattice.chronicle.models.ExtractedUsageEvent

/**
 * Per-event dedupe seam for the device-lifecycle module (refactor plan §8.1 step 17
 * "preserve dedupe window", §8.2 step 15 "idempotency tests for repeated broadcasts").
 *
 * Lifecycle broadcasts arrive in bursts — a screen-on can be delivered twice within
 * milliseconds, and the connectivity-change sampler can re-emit an unchanged state.
 * The pre-Phase-5 `DeviceLifecycleEventRecorder` suppressed a repeat of the *same*
 * `(interactionType, activityClass)` event seen within a 2-second window, using a
 * shared-preferences file. This interface is that exact behaviour behind a seam so the
 * module can be unit-tested on the JVM without Android `SharedPreferences`.
 *
 */
public interface LifecycleDedupeStore {

    /**
     * Returns `true` if [event] should be persisted, `false` if it is a duplicate of a
     * recently-seen event and must be dropped.
     *
     * A `true` result records [now] as the last-seen time for this event's dedupe key,
     * so an immediate repeat within the dedupe window returns `false`.
     */
    public fun shouldPersist(event: ExtractedUsageEvent, now: Long): Boolean
}

/**
 * Shared-preferences file backing the production lifecycle dedupe store.
 *
 * `public` (not `internal`) so it is visible across the module boundary — `:app`'s
 * instrumented tests and the legacy `DeviceLifecycleEvents` dedupe path reference this
 * exact file name, and they now live in a different Gradle module.
 */
public const val LIFECYCLE_RECORDER_PREFS_NAME: String = "chronicle_lifecycle_recorder"

/** Dedupe window: a repeat of the same lifecycle event within this many millis is dropped. */
public const val LIFECYCLE_DEDUPE_WINDOW_MS: Long = 2_000L

/**
 * Production [LifecycleDedupeStore] backed by the `chronicle_lifecycle_recorder`
 * shared-preferences file.
 *
 * This preserves the pre-Phase-5 behaviour **exactly** — same prefs file
 * ([LIFECYCLE_RECORDER_PREFS_NAME]), same key format (`last:<interactionType>:<activityClass>`),
 * same [LIFECYCLE_DEDUPE_WINDOW_MS] window — so the dedupe state survives the migration
 * whether [LifecycleWorkerMigration.USE_MODULE_MANAGER_LIFECYCLE_PATH] is on or off, and
 * the two paths suppress identically.
 *
 * It is constructed per use from a [Context]; it holds no `Context` in a long-lived
 * field — only the resolved [android.content.SharedPreferences] handle.
 */
public class PrefsLifecycleDedupeStore(context: Context) : LifecycleDedupeStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(LIFECYCLE_RECORDER_PREFS_NAME, Context.MODE_PRIVATE)

    override fun shouldPersist(event: ExtractedUsageEvent, now: Long): Boolean {
        val key = "last:${event.interactionType}:${event.activityClass ?: ""}"
        val last = prefs.getLong(key, Long.MIN_VALUE)
        if (last != Long.MIN_VALUE && now - last < LIFECYCLE_DEDUPE_WINDOW_MS) {
            return false
        }
        prefs.edit().putLong(key, now).apply()
        return true
    }
}
