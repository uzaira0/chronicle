package com.openlattice.chronicle.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reviewer notes must name the controls the app actually shows, and spell out the whole
 * notification path the unlock prompt needs; otherwise a reviewer (or a participant following
 * the same steps) enables the feature and never sees the prompt.
 */
class ReviewerNotesContractTest {
    private val strings = File("src/main/res/values/strings.xml").readText()
    private val notes = listOf(
        File("../store/play/reviewer-instructions.md").readText(),
        File("../store/play/foreground-service-declaration.md").readText(),
    ).map { it.replace(Regex("\\s+"), " ") }

    private fun string(name: String): String =
        Regex("""<string name="$name">([^<]*)</string>""").find(strings)?.groupValues?.get(1)
            ?: error("missing string $name")

    @Test
    fun notesUseTheRealControlAndChannelLabels() {
        val control = string("settings_identify_device_user")
        val channel = string("identify_user_channel_name")
        val openSettings = string("settings_open_notification_settings")
        notes.forEach { note ->
            assertTrue(note.contains(control))
            assertFalse("stale label 'Identify user'", Regex("""Identify user\b""").containsMatchIn(note))
        }
        val reviewer = notes.first()
        listOf(channel, openSettings, "Pop on screen", "Allow", "Usage access").forEach {
            assertTrue("reviewer notes miss: $it", reviewer.contains(it))
        }
    }

    @Test
    fun inAppRecoveryCopyUsesTheRealControlLabel() {
        assertFalse(string("settings_notifications_body").contains("Identify User"))
        assertTrue(string("settings_notifications_body").contains(string("settings_identify_device_user")))
    }
}
