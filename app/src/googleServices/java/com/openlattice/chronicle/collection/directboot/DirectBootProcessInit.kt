package com.openlattice.chronicle.collection.directboot

import android.content.Context
import android.util.Log
import androidx.emoji2.text.EmojiCompatInitializer
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.startup.AppInitializer

private val TAG = DirectBootProcessInit::class.java.simpleName

/**
 * Re-runs the `androidx.startup` initializers a direct-boot-started process skipped.
 *
 * A process created before first unlock never instantiates the (non-`directBootAware`)
 * `InitializationProvider`, and providers are not created retroactively — so
 * `ProcessLifecycleOwner` and `EmojiCompat` would stay unconfigured for that process's whole
 * lifetime, breaking any post-unlock UI opened into it. [AppInitializer.initializeComponent]
 * is idempotent, so calling this from the unlock handover (and `StartOnBoot`) is a no-op in
 * normally-started processes. WorkManager is *not* re-run here — it uses on-demand
 * initialization via `ChronicleApplication` instead.
 *
 * Must run on the main thread (lifecycle registration requires it); both call sites are
 * broadcast deliveries, which are.
 */
object DirectBootProcessInit {

    fun reinitializeAfterUnlock(context: Context) {
        val initializer = AppInitializer.getInstance(context.applicationContext)
        runCatching { initializer.initializeComponent(ProcessLifecycleInitializer::class.java) }
            .onFailure { Log.w(TAG, "ProcessLifecycle re-init failed (non-fatal)", it) }
        runCatching { initializer.initializeComponent(EmojiCompatInitializer::class.java) }
            .onFailure { Log.w(TAG, "EmojiCompat re-init failed (non-fatal)", it) }
    }
}
