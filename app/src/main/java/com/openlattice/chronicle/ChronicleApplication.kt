package com.openlattice.chronicle

import android.app.Application
import android.os.UserManager
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.security.InvitedServerCa
import androidx.work.Configuration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.profileinstaller.ProfileInstallerInitializer
import androidx.startup.AppInitializer
import com.openlattice.chronicle.services.upload.installPreAndroid11CrashHandler

/**
 * Application shell for WorkManager **on-demand initialization** ([Configuration.Provider]).
 *
 * Lifecycle uses the native direct-boot-aware Startup provider. WorkManager's initializer
 * is removed because its database needs credential storage; the configuration provider
 * initializes it on first post-unlock use, including processes that started while locked.
 *
 * **This class must never touch credential-encrypted storage** (the SQLCipher Room DB,
 * `EncryptedSharedPreferences`) — `onCreate` runs pre-unlock in a direct-boot process, where
 * that storage does not exist yet.
 */
class ChronicleApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        installPreAndroid11CrashHandler(this)
        InvitedServerCa.attach(this)
        registerActivityLifecycleCallbacks(SystemBarInsets)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                AppInitializer.getInstance(this@ChronicleApplication).initializeComponent(ProfileInstallerInitializer::class.java)
                owner.lifecycle.removeObserver(this)
            }
        })
        if (getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
            ResearchPersistenceGate.initializeAsync(this)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setInitializationExceptionHandler { error ->
                WorkSchedulingStatus.initializationFailed(error, baseContext?.applicationContext, reconcileStartup = true)
            }
            .build()
}
