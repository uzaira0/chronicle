package com.openlattice.chronicle

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The battery and "Keep Chronicle Active" dialogs tell the participant which Settings switch to use, so
 * each Android version must quote the label that version's Settings shows. Labels are AOSP
 * Settings / PermissionController strings at android-N.0.0_r1 (Spanish: values-es):
 *  - 8–14: IGNORE_BATTERY_OPTIMIZATION_SETTINGS opens "Battery optimization" (high_power_apps), whose
 *    filter starts on "Not optimized"; the per-app dialog offers "Don’t optimize" (ignore_optimizations_on).
 *  - 15+: the intent opens "App battery usage" (AppBatteryUsageActivity, app_battery_usage_title), the app
 *    page has "Allow background usage", then "Unrestricted".
 *  - Hibernation: 11 has the switch under App info > Permissions ("Remove permissions if app isn’t used");
 *    12 "Remove permissions and free up space"; 13–14 "Pause app activity if unused"; 15+ (app archiving
 *    on) "Manage app if unused".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 30, 31, 32, 33, 34, 35, 36])
class SettingsLabelCopyTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun eachAndroidVersionNamesItsOwnSettingsLabels() {
        val sdk = Build.VERSION.SDK_INT
        for (language in listOf("en", "es")) {
            val localized = app.createConfigurationContext(
                Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
            check(language, "battery", localized.getString(R.string.battery_optimization_exemption),
                battery(language, sdk), BATTERY.getValue(language).values.flatten())
            if (sdk >= Build.VERSION_CODES.R) {
                check(language, "hibernation", localized.getString(R.string.hibernation_exemption),
                    hibernation(language, sdk), HIBERNATION.getValue(language).values.flatten())
            }
        }
    }

    private fun check(language: String, dialog: String, text: String, expected: List<String>, all: List<String>) {
        val sdk = Build.VERSION.SDK_INT
        expected.forEach { assertTrue("API $sdk $language $dialog text must name $it: $text", it in text) }
        (all - expected.toSet()).filter { it.startsWith("\"") }
            .forEach { assertFalse("API $sdk $language $dialog text names another version's $it: $text", it in text) }
    }

    private fun battery(language: String, sdk: Int) = BATTERY.getValue(language).let {
        when {
            sdk >= 36 && "36" in it -> it.getValue("36")
            sdk >= 35 -> it.getValue("35")
            else -> it.getValue("26")
        }
    }

    private fun hibernation(language: String, sdk: Int) = HIBERNATION.getValue(language).getValue(
        when {
            sdk >= 35 -> "35"
            sdk >= 33 -> "33"
            sdk >= 31 -> "31"
            else -> "30"
        })

    private companion object {
        val BATTERY = mapOf(
            "en" to mapOf(
                "26" to listOf("Battery optimization", "\"All apps\"", "\"Don’t optimize\""),
                "35" to listOf("App battery usage", "\"Allow background usage\"", "\"Unrestricted\""),
            ),
            "es" to mapOf(
                "26" to listOf("Optimización de batería", "\"Todas las aplicaciones\"", "\"No optimizar\""),
                "35" to listOf("Uso de batería en la aplicación", "\"Permitir uso en segundo plano\"",
                    "\"Sin restricciones\""),
                "36" to listOf("Uso de batería de la aplicación", "\"Permitir uso en segundo plano\"",
                    "\"Sin restricciones\""),
            ),
        )
        val HIBERNATION = mapOf(
            "en" to mapOf(
                "30" to listOf("Permissions", "\"Remove permissions if app isn’t used\""),
                "31" to listOf("\"Remove permissions and free up space\""),
                "33" to listOf("\"Pause app activity if unused\""),
                "35" to listOf("\"Manage app if unused\""),
            ),
            "es" to mapOf(
                "30" to listOf("Permisos", "\"Quitar permisos si la aplicación no se utiliza\""),
                "31" to listOf("\"Quitar permisos y liberar espacio\""),
                "33" to listOf("\"Pausar actividad de la aplicación si no se usa\""),
                "35" to listOf("\"Gestionar la aplicación si no se usa\""),
            ),
        )
    }
}
