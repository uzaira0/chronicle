package com.openlattice.chronicle

import android.app.Application
import androidx.work.Configuration

/**
 * Application shell for WorkManager **on-demand initialization** ([Configuration.Provider]).
 *
 * Needed by direct-boot collection (2026-07-15): a process started before the user's first
 * unlock skips every non-`directBootAware` ContentProvider — including the `androidx.startup`
 * `InitializationProvider` that auto-initializes WorkManager — and providers are never
 * instantiated later in that process's lifetime. Without on-demand initialization, the first
 * `WorkManager.getInstance(context)` after unlock (the direct-boot buffer drain, or
 * `StartOnBoot`'s worker scheduling delivered into the still-alive process) would throw.
 * With it, `getInstance(context)` self-initializes on first use in every process, direct-boot
 * or not. The `WorkManagerInitializer` entry is removed from the manifest accordingly
 * (`tools:node="remove"`), as on-demand initialization requires.
 *
 * **This class must never touch credential-encrypted storage** (the SQLCipher Room DB,
 * `EncryptedSharedPreferences`) — `onCreate` runs pre-unlock in a direct-boot process, where
 * that storage does not exist yet.
 */
class ChronicleApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
