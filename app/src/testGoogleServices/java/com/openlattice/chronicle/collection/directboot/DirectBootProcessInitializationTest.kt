package com.openlattice.chronicle.collection.directboot

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Bundle
import com.openlattice.chronicle.ANDROID_NS
import com.openlattice.chronicle.mergedMainManifest
import org.w3c.dom.Element
import android.os.UserManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.AppInitializer
import androidx.startup.InitializationProvider
import androidx.profileinstaller.ProfileInstallerInitializer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DirectBootProcessInitializationTest {
    @Test fun lockedProcessUsesTheNativeEagerLifecycleInitializer() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val providers = mergedMainManifest().getElementsByTagName("provider")
        val declaration = (0 until providers.length).map { providers.item(it) as Element }
            .single { it.getAttributeNS(ANDROID_NS, "name") == InitializationProvider::class.java.name }
        val provider = ProviderInfo().apply {
            applicationInfo = context.applicationInfo
            authority = declaration.getAttributeNS(ANDROID_NS, "authorities")
            directBootAware = declaration.getAttributeNS(ANDROID_NS, "directBootAware").toBoolean()
            metaData = Bundle().apply {
                val entries = declaration.getElementsByTagName("meta-data")
                repeat(entries.length) {
                    val entry = entries.item(it) as Element
                    putString(entry.getAttributeNS(ANDROID_NS, "name"), entry.getAttributeNS(ANDROID_NS, "value"))
                }
            }
        }
        assertTrue("The native startup provider must run for a locked process", provider.directBootAware)
        assertEquals("androidx.startup", provider.metaData.getString(ProcessLifecycleInitializer::class.java.name))
        assertFalse("Profile installation must wait for an unlocked UI", provider.metaData.containsKey(ProfileInstallerInitializer::class.java.name))
        Robolectric.buildContentProvider(InitializationProvider::class.java).create(provider)
        assertTrue(AppInitializer.getInstance(context).isEagerlyInitialized(ProcessLifecycleInitializer::class.java))
        assertTrue(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED))
    }
}
