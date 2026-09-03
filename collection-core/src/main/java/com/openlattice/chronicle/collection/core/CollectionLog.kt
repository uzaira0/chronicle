package com.openlattice.chronicle.collection.core

import android.util.Log

/**
 * Logging helper for the data collection core (design §1C.4, refactor plan §6.1 step 13).
 *
 * The collection core never calls [android.util.Log] directly. Sinks, the registry and
 * the settings resolver log through this interface so that:
 *  - JVM unit tests can inject a no-op or capturing implementation without the
 *    `android.util.Log` stub throwing (`returnDefaultValues` is not enabled and Phase 3
 *    must not modify `build.gradle`);
 *  - the "invalid setting logs and disables" rule (design §1B.4) and the
 *    "persistent failures are never silently swallowed" rule (design §1C.2) have a
 *    single, testable, mockable seam.
 *
 */
public interface CollectionLog {
    /** Logs an informational message. */
    public fun info(tag: String, message: String)

    /** Logs a warning, optionally with a cause. */
    public fun warn(tag: String, message: String, error: Throwable? = null)

    /** Logs an error, optionally with a cause. */
    public fun error(tag: String, message: String, error: Throwable? = null)

    public companion object {
        /** Production logger backed by Logcat. */
        public val LOGCAT: CollectionLog = LogcatCollectionLog
    }
}

/**
 * Production [CollectionLog] backed by [android.util.Log]. Holds no [android.content.Context]
 * and no mutable state, so it is safe to expose as a singleton object.
 *
 * Never instantiated by JVM unit tests — tests use [RecordingCollectionLog] or a no-op.
 */
public object LogcatCollectionLog : CollectionLog {
    override fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun warn(tag: String, message: String, error: Throwable?) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
    }
}

/**
 * No-op [CollectionLog]. Discards every message; useful as a default in tests that do
 * not assert on log output.
 */
public object NoOpCollectionLog : CollectionLog {
    override fun info(tag: String, message: String) {}
    override fun warn(tag: String, message: String, error: Throwable?) {}
    override fun error(tag: String, message: String, error: Throwable?) {}
}

/**
 * Capturing [CollectionLog] for tests. Records every message at every level so tests
 * can assert that an invalid setting or a persistent sink failure was actually logged.
 */
public class RecordingCollectionLog : CollectionLog {

    /** A single captured log line. */
    public data class Entry(
        val level: Level,
        val tag: String,
        val message: String,
        val error: Throwable?,
    )

    /** Log severity of a captured [Entry]. */
    public enum class Level { INFO, WARN, ERROR }

    private val captured = mutableListOf<Entry>()

    /** All captured entries, in order. */
    public val entries: List<Entry> get() = captured.toList()

    /** All captured [Level.WARN] and [Level.ERROR] entries. */
    public val problems: List<Entry>
        get() = captured.filter { it.level == Level.WARN || it.level == Level.ERROR }

    override fun info(tag: String, message: String) {
        captured.add(Entry(Level.INFO, tag, message, null))
    }

    override fun warn(tag: String, message: String, error: Throwable?) {
        captured.add(Entry(Level.WARN, tag, message, error))
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        captured.add(Entry(Level.ERROR, tag, message, error))
    }
}
