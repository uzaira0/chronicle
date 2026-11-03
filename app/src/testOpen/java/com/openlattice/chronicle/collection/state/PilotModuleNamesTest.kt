package com.openlattice.chronicle.collection.state

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Every module the pilot study enables (docs/PILOT-DATA.md) is named on the consent
 * plan and Data Sharing screens from curated resources, in English and in Spanish. The id-derived
 * fallback ("Upload Telemetry") is English only and must never reach a participant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PilotModuleNamesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun everyPilotModuleHasCuratedEnglishAndSpanishNames() {
        val spanish = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("es-CL")) },
        )
        PILOT_MODULES.forEach { moduleId ->
            assertTrue("$moduleId must be collectable in the Play build",
                DistributionModulePolicy.supports(DistributionChannel.current(), moduleId))
            assertTrue("$moduleId resolves through the id-derived English fallback",
                CollectionConsentCopy.hasCuratedNames(moduleId))
            val en = CollectionConsentCopy.template(context, moduleId)
            val es = CollectionConsentCopy.template(spanish, moduleId)
            assertTrue("$moduleId needs a label", en.label.isNotBlank() && es.label.isNotBlank())
            assertNotEquals("$moduleId label is not translated", en.label, es.label)
            assertNotEquals("$moduleId privacy class is not translated", en.privacyClass, es.privacyClass)
        }
    }

    private companion object {
        val PILOT_MODULES = listOf(
            CollectionModuleId.USAGE_EVENTS,
            CollectionModuleId.IN_APP_ACTIVITY_CLASS,
            CollectionModuleId.DEVICE_LIFECYCLE,
            CollectionModuleId.USER_IDENTIFICATION,
            CollectionModuleId.UPLOAD_TELEMETRY,
            CollectionModuleId.SENSOR_AVAILABILITY,
            CollectionModuleId.BATTERY_TELEMETRY,
        )
    }
}
