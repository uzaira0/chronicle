package com.openlattice.chronicle.collection.interaction

import android.accessibilityservice.AccessibilityService
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.InteractionEventType
import com.openlattice.chronicle.collection.InteractionPositionSource
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.InteractionSampleEntry
import com.openlattice.chronicle.storage.interactionSampleDao
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Single-worker executor with an explicit queue bound and observable rejection/shutdown loss. */
internal class BoundedInteractionTaskExecutor(
    capacity: Int,
    private val onDropped: (Int) -> Unit,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity),
        ThreadFactory { runnable -> Thread(runnable, "chronicle-interaction-persistence") },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun execute(task: () -> Unit): Boolean = try {
        executor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        onDropped(1)
        false
    }

    /** Stops the worker and reports tasks abandoned from the bounded queue. */
    fun shutdownNow(): Int = executor.shutdownNow().size.also { abandoned ->
        if (abandoned > 0) onDropped(abandoned)
    }
}

/**
 * The `interaction_events` collection module's on-device collector — an [AccessibilityService]
 * that records interaction *salience* (where attention lands), not content
 * (`docs/SENSING-EXPANSION-DESIGN.md` §6).
 *
 * It observes taps, long-presses, scrolls, input-focus, accessibility-focus, and item-selection
 * events ([interactionTypeFor]). For each it records the accessibility node's raw screen bounds,
 * display id/size/rotation/density, and explicit [InteractionPositionSource]. Android view
 * accessibility events do not expose the user's exact pointer coordinate, and this service does
 * not request an input source because Android would stop delivering that source to applications.
 * The old center/normalized/grid values are emitted only as compatibility derivations; the
 * interacted element's *role* (its view class name); the foreground package; and the salience
 * kinematics — the monotonic event time, an episode id grouping interaction bursts, the
 * inter-event dwell, and (for scrolls) the delta, velocity, and direction-reversal flag. It
 * **never** reads the element's text or `contentDescription`; content-freeness is structural,
 * not a setting.
 *
 * Like the other service-realized modules (the `sensor_*` modules, `usage_events`), it is not a
 * registry `DataCollectionModule`. Collection is gated exactly like them:
 * an active, study-bound runtime-policy snapshot **and** [CollectionGate.collects] for
 * [CollectionModuleId.INTERACTION_EVENTS] (which the participant turns on by accepting the
 * module in the enrollment wizard / Data Sharing tab) — plus the participant enabling this
 * service in system Accessibility settings. When the gate is closed, every event is a cheap
 * no-op. Rows are drained by [InteractionUploadWorker].
 *
 * Grid granularity + clicks/scrolls/element-position toggles are one atomic in-memory snapshot, so a
 * settings sync takes effect without cycling the Accessibility service or performing encrypted
 * reads per event. Per-event writes use one bounded background queue; overload and shutdown drops
 * are counted and logged instead of allowing unbounded memory growth.
 */
class InteractionCollectionService : AccessibilityService() {

    private val droppedPersistenceTasks = AtomicLong()
    private val persistenceFailures = AtomicLong()
    private val writeExecutor = BoundedInteractionTaskExecutor(
        capacity = PERSISTENCE_QUEUE_CAPACITY,
        onDropped = ::recordDroppedPersistenceTasks,
    )
    private var policySettings: InteractionPolicySettings? = null
    private var loggedPolicyUnavailable = false

    // Episode/kinematics state. onAccessibilityEvent is delivered single-threaded, so these need
    // no locking. An "episode" is a continuous burst of interactions; it resets after an idle gap.
    private var lastEventUptimeMillis: Long? = null
    private var currentEpisodeId: String? = null
    private var lastScrollDominantSign: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Initialize encrypted storage once at service connection. Retrying a failed lazy
        // initializer on every accessibility event would turn a storage failure into hot-path I/O.
        policySettings = try {
            InteractionPolicySettings(applicationContext)
        } catch (error: RuntimeException) {
            loggedPolicyUnavailable = true
            Log.e(TAG, "Interaction policy store unavailable; collection closed until reconnect", error)
            null
        }
        // Ensure the periodic upload drains whatever this service records.
        scheduleInteractionUploadWork(applicationContext)
        Log.i(TAG, "Interaction accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = interactionTypeFor(event.eventType) ?: return
        val runtimePolicySettings = policySettings ?: return
        val policySnapshot = runtimePolicySettings.currentSnapshot()
        if (policySnapshot == null) {
            if (!loggedPolicyUnavailable) {
                loggedPolicyUnavailable = true
                Log.w(TAG, "No active interaction-policy generation; collection remains closed")
            }
            return
        }
        if (loggedPolicyUnavailable) {
            loggedPolicyUnavailable = false
            Log.i(TAG, "Interaction-policy generation restored")
        }
        val policy = policySnapshot.policy
        if (eventType == InteractionEventType.SCROLL && !policy.captureScrolls) return
        if (eventType != InteractionEventType.SCROLL && !policy.captureClicks) return

        // Policy publication proves active enrollment for this study generation. The persisted
        // gate independently proves the participant accepted this module. Accessibility events
        // arrive on the main thread, so the Room-backed gate must be checked by writeExecutor
        // immediately before persistence rather than here.
        val ctx = applicationContext

        val bounds = Rect()
        // event.source is null when the framework can't supply the node (e.g. secure windows);
        // without bounds we cannot place the interaction in a region, so skip it.
        val node = event.source ?: return
        node.getBoundsInScreen(bounds)
        // Release the obtained node promptly. recycle() is a documented no-op (deprecated) on
        // API 33+, but on 26–32 the node must be recycled or a high-frequency scroll burst
        // leaks nodes and pressures the framework's AccessibilityNodeInfo pool.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            node.recycle()
        }

        val displayContext = displayContextFor(event)
        val legacyGridPosition = deriveLegacyInteractionGridPosition(
            bounds = InteractionNodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            displayWidth = displayContext.widthPixels,
            displayHeight = displayContext.heightPixels,
            gridRows = policy.gridRows,
            gridCols = policy.gridCols,
        )

        // Raw node bounds are the authoritative observation. The required grid values remain for
        // wire compatibility, but new rows deliberately leave rawX/rawY and normalizedX/Y null:
        // those fields were misnamed derived node centers and must never be mistaken for a tap.
        val captureElementPosition = policy.captureElementPosition

        // Monotonic event time (uptime domain). Authoritative for ordering + kinematics; we also
        // anchor it to an exact wall-clock so the stored timestamp matches the real event instant.
        val eventUptime = event.eventTime
        val (episodeId, dwellMillisSincePrev) = advanceEpisode(eventUptime)

        // Role = the view's class name (e.g. "android.widget.Button"). Never element text.
        val elementRole = event.className?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"
        val foregroundPackage = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"
        val (scrollDeltaX, scrollDeltaY) = scrollDeltas(event, eventType)
        val kinematics = scrollKinematics(eventType, scrollDeltaX, scrollDeltaY, dwellMillisSincePrev)

        val entry = InteractionSampleEntry(
            id = UUID.randomUUID().toString(),
            timestamp = anchoredWallClock(eventUptime),
            timezone = ZoneId.systemDefault().id,
            eventType = eventType.name,
            gridRows = policy.gridRows,
            gridCols = policy.gridCols,
            gridRow = legacyGridPosition.gridRow,
            gridCol = legacyGridPosition.gridCol,
            elementRole = elementRole,
            foregroundPackage = foregroundPackage,
            positionSource = InteractionPositionSource.ACCESSIBILITY_NODE_BOUNDS.name
                .takeIf { captureElementPosition },
            nodeBoundsLeft = bounds.left.takeIf { captureElementPosition },
            nodeBoundsTop = bounds.top.takeIf { captureElementPosition },
            nodeBoundsRight = bounds.right.takeIf { captureElementPosition },
            nodeBoundsBottom = bounds.bottom.takeIf { captureElementPosition },
            displayId = displayContext.displayId.takeIf { captureElementPosition },
            rawX = null,
            rawY = null,
            screenWidth = displayContext.widthPixels.takeIf { captureElementPosition },
            screenHeight = displayContext.heightPixels.takeIf { captureElementPosition },
            normalizedX = null,
            normalizedY = null,
            scrollDeltaX = scrollDeltaX,
            scrollDeltaY = scrollDeltaY,
            eventTimeMillis = eventUptime,
            episodeId = episodeId,
            dwellMillisSincePrev = dwellMillisSincePrev,
            orientation = displayContext.rotation.takeIf { captureElementPosition },
            screenDensityDpi = displayContext.densityDpi.takeIf { captureElementPosition },
            scrollVelocityX = kinematics.velocityX,
            scrollVelocityY = kinematics.velocityY,
            scrollReversed = kinematics.reversed,
        )

        writeExecutor.execute {
            try {
                // A settings/enrollment transition invalidates the captured generation before it
                // changes durable state, so queued events can never persist under stale policy.
                if (!runtimePolicySettings.isCurrent(policySnapshot)) return@execute
                ResearchPersistenceGate.persistIfCollecting(
                    ctx,
                    CollectionModuleId.INTERACTION_EVENTS,
                ) {
                    ChronicleDb.getInstance(ctx).interactionSampleDao().insertAll(listOf(entry))
                }
            } catch (e: Exception) {
                recordPersistenceFailure(e)
            }
        }
    }

    override fun onDestroy() {
        val abandoned = writeExecutor.shutdownNow()
        Log.i(
            TAG,
            "Interaction persistence stopped: abandoned=$abandoned, " +
                "droppedTotal=${droppedPersistenceTasks.get()}, failures=${persistenceFailures.get()}",
        )
        super.onDestroy()
    }

    override fun onInterrupt() {
        // No-op: the framework calls this when accessibility feedback should stop; we record
        // events passively and hold no feedback state to interrupt.
    }

    private fun interactionTypeFor(accessibilityEventType: Int): InteractionEventType? = when (accessibilityEventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> InteractionEventType.CLICK
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> InteractionEventType.LONG_CLICK
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> InteractionEventType.SCROLL
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> InteractionEventType.FOCUS
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> InteractionEventType.ACCESSIBILITY_FOCUS
        AccessibilityEvent.TYPE_VIEW_SELECTED -> InteractionEventType.SELECT
        else -> null
    }

    private fun scrollDeltas(event: AccessibilityEvent, eventType: InteractionEventType): Pair<Int?, Int?> {
        if (eventType != InteractionEventType.SCROLL || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null to null
        }
        // getScrollDeltaX/Y return -1 when the platform cannot supply a delta; treat that as unknown.
        val dx = event.scrollDeltaX.takeIf { it != -1 }
        val dy = event.scrollDeltaY.takeIf { it != -1 }
        return dx to dy
    }

    /**
     * Assigns this event to an episode and returns `(episodeId, dwellMillisSincePrev)`. A gap
     * longer than [EPISODE_IDLE_GAP_MILLIS] (or a missing/backwards monotonic clock) starts a new
     * episode; the first event of an episode has a `null` dwell. Updates the rolling state.
     */
    private fun advanceEpisode(eventUptime: Long): Pair<String, Long?> {
        val prev = lastEventUptimeMillis
        val gap = if (prev != null) eventUptime - prev else null
        val newEpisode = currentEpisodeId == null || gap == null || gap < 0 || gap > EPISODE_IDLE_GAP_MILLIS
        val episodeId = if (newEpisode) {
            UUID.randomUUID().toString().also {
                currentEpisodeId = it
                lastScrollDominantSign = 0
            }
        } else {
            currentEpisodeId!!
        }
        lastEventUptimeMillis = eventUptime
        return episodeId to gap?.takeUnless { newEpisode }
    }

    /**
     * Derives scroll velocity (px/sec from the deltas and the inter-event dwell) and whether this
     * scroll reversed the previous scroll's dominant direction. Returns nulls for non-scroll
     * events. Updates [lastScrollDominantSign] for the next comparison.
     */
    private fun scrollKinematics(
        eventType: InteractionEventType,
        scrollDeltaX: Int?,
        scrollDeltaY: Int?,
        dwellMillisSincePrev: Long?,
    ): ScrollKinematics {
        if (eventType != InteractionEventType.SCROLL) return ScrollKinematics(null, null, null)
        val dt = dwellMillisSincePrev
        val velocityX = if (dt != null && dt > 0 && scrollDeltaX != null) scrollDeltaX * MILLIS_PER_SECOND / dt else null
        val velocityY = if (dt != null && dt > 0 && scrollDeltaY != null) scrollDeltaY * MILLIS_PER_SECOND / dt else null
        // Dominant axis = the one with the larger magnitude; its sign drives reversal detection.
        val dominant = when {
            scrollDeltaY != null && (scrollDeltaX == null || abs(scrollDeltaY) >= abs(scrollDeltaX)) -> scrollDeltaY
            else -> scrollDeltaX
        }
        var reversed: Boolean? = null
        if (dominant != null && dominant != 0) {
            val sign = if (dominant > 0) 1 else -1
            if (lastScrollDominantSign != 0) reversed = sign != lastScrollDominantSign
            lastScrollDominantSign = sign
        }
        return ScrollKinematics(velocityX, velocityY, reversed)
    }

    private data class ScrollKinematics(val velocityX: Double?, val velocityY: Double?, val reversed: Boolean?)

    /** Anchors the monotonic [eventUptime] to an exact UTC wall-clock instant. */
    private fun anchoredWallClock(eventUptime: Long): String {
        val wallMillis = System.currentTimeMillis() - (SystemClock.uptimeMillis() - eventUptime)
        return Instant.ofEpochMilli(wallMillis).atOffset(ZoneOffset.UTC).toString()
    }

    /** Captures the logical display coordinate space corresponding to [event]'s node bounds. */
    private fun displayContextFor(event: AccessibilityEvent): InteractionDisplayContext {
        // Some Fire OS API-30 builds omit AccessibilityEvent.getDisplayId even though the AOSP
        // API level advertises it. Reflective capability detection avoids a process-killing
        // NoSuchMethodError and correctly falls back to the single default display there.
        val eventDisplayId = accessibilityEventDisplayIdMethod
            ?.let { method -> runCatching { method.invoke(event) as? Int }.getOrNull() }
            ?.takeIf { it >= 0 }
        val requestedDisplayId = eventDisplayId ?: Display.DEFAULT_DISPLAY
        val displayManager = getSystemService(DisplayManager::class.java)
        val display = displayManager?.getDisplay(requestedDisplayId)
            ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        val metrics = DisplayMetrics()
        if (display != null) {
            // AccessibilityNodeInfo.getBoundsInScreen is a display-level coordinate. These
            // deprecated APIs remain the only cross-minSdk way to query that same display extent
            // from a non-Activity service; WindowMetrics would describe Chronicle's own window.
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            val fallback = resources.displayMetrics
            size.set(fallback.widthPixels, fallback.heightPixels)
            metrics.setTo(fallback)
        }
        return InteractionDisplayContext(
            displayId = display?.displayId ?: requestedDisplayId,
            widthPixels = size.x.coerceAtLeast(1),
            heightPixels = size.y.coerceAtLeast(1),
            densityDpi = metrics.densityDpi.coerceAtLeast(1),
            rotation = display?.rotation,
        )
    }

    private data class InteractionDisplayContext(
        val displayId: Int,
        val widthPixels: Int,
        val heightPixels: Int,
        val densityDpi: Int,
        val rotation: Int?,
    )

    private fun recordDroppedPersistenceTasks(count: Int) {
        val total = droppedPersistenceTasks.addAndGet(count.toLong())
        val previous = total - count
        if (previous == 0L || previous / TELEMETRY_LOG_INTERVAL != total / TELEMETRY_LOG_INTERVAL) {
            Log.w(TAG, "Dropped interaction persistence task(s): total=$total")
        }
    }

    private fun recordPersistenceFailure(error: Exception) {
        val total = persistenceFailures.incrementAndGet()
        if (total == 1L || total % TELEMETRY_LOG_INTERVAL == 0L) {
            Log.w(TAG, "Failed to persist interaction event: total=$total", error)
        }
    }

    companion object {
        private const val TAG = "InteractionCollection"
        private val accessibilityEventDisplayIdMethod = runCatching {
            AccessibilityEvent::class.java.getMethod("getDisplayId")
        }.getOrNull()
        private const val PERSISTENCE_QUEUE_CAPACITY = 512
        private const val TELEMETRY_LOG_INTERVAL = 100L

        /** A gap longer than this (ms) between interactions starts a new episode. */
        private const val EPISODE_IDLE_GAP_MILLIS = 30_000L
        private const val MILLIS_PER_SECOND = 1000.0
    }
}
