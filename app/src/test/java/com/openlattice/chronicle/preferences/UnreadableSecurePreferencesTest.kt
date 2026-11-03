package com.openlattice.chronicle.preferences

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Recovery preserves and replaces a secure preference store that no longer opens, without opening it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class UnreadableSecurePreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = File(context.applicationInfo.dataDir, "shared_prefs/chronicle_encrypted_prefs.xml")

    @Test fun anUnreadableStoreIsFoundForPreservationAndThenReplaced() {
        store.parentFile!!.mkdirs()
        store.writeText("<map><string name=\"corrupt\">keyset that no longer decrypts</string></map>")

        val previous = EnrollmentTestStores.prefs
        // The native-encryption shadow must refuse this store even if an earlier suite installed readable preferences.
        EnrollmentTestStores.prefs = null
        try {
            assertEquals(listOf(store.canonicalFile), EncryptedPrefsHelper.unreadableStores(context).map(File::getCanonicalFile))
            assertTrue(EncryptedPrefsHelper.replaceUnreadableStore(context))
            assertFalse("The preserved store is removed so the next open starts empty", store.exists())
        } finally { EnrollmentTestStores.prefs = previous }
    }

    @Test fun aStoreThatOpensButCannotReadIsFoundForPreservation() {
        store.parentFile!!.mkdirs()
        store.writeText("unreadable ciphertext")
        val previous = EnrollmentTestStores.prefs
        EnrollmentTestStores.prefs = object : SharedPreferences by context.getSharedPreferences("unreadable-test", Context.MODE_PRIVATE) {
            override fun getAll(): Map<String, *> = throw SecurityException("value no longer decrypts")
        }
        try {
            assertEquals(listOf(store.canonicalFile), EncryptedPrefsHelper.unreadableStores(context).map(File::getCanonicalFile))
            assertTrue(EncryptedPrefsHelper.replaceUnreadableStore(context))
            assertFalse(store.exists())
        } finally { EnrollmentTestStores.prefs = previous }
    }
}
