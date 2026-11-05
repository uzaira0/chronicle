package com.openlattice.chronicle.collection.state

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The core data-choice steps use names a participant would use, and the device-state step says how that state is
 * recorded (sampled now and then, not watched by a running service) without the word "supplemental".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlainModuleCopyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun coreStepsUseParticipantNames() = assertTitles(
        "App use", "Device state", "Who is using the device")

    @Test @Config(qualifiers = "es-rCL")
    fun coreStepsUseParticipantNamesInSpanish() = assertTitles(
        "Uso de aplicaciones", "Estado del dispositivo", "Quién usa el dispositivo")

    @Test fun deviceStateSaysWhenItIsRecorded() {
        val collects = CollectionConsentCopy.template(context, CollectionModuleId.DEVICE_LIFECYCLE).whatItCollects
        val text = collects.joinToString("\n")
        assertFalse(text, text.contains("upplemental"))
        listOf("when the phone starts", "when you open Chronicle", "about every 15 minutes").forEach {
            assertTrue("device state must say '$it': $text", it in text)
        }
    }

    @Test @Config(qualifiers = "es-rCL")
    fun deviceStateSaysWhenItIsRecordedInSpanish() {
        val text = CollectionConsentCopy.template(context, CollectionModuleId.DEVICE_LIFECYCLE).whatItCollects.joinToString("\n")
        assertFalse(text, text.contains("omplementari"))
        listOf("al encender el teléfono", "al abrir Chronicle", "cada 15 minutos").forEach {
            assertTrue("device state must say '$it': $text", it in text)
        }
    }

    private fun assertTitles(usage: String, lifecycle: String, identification: String) {
        assertEquals(usage, CollectionConsentCopy.label(context, CollectionModuleId.USAGE_EVENTS))
        assertEquals(lifecycle, CollectionConsentCopy.label(context, CollectionModuleId.DEVICE_LIFECYCLE))
        assertEquals(identification, CollectionConsentCopy.label(context, CollectionModuleId.USER_IDENTIFICATION))
    }
}
