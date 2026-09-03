package com.openlattice.chronicle.collection.interaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Prevents interaction collection from silently taking ownership of participant touch input. */
class InteractionInputNonInterferenceContractTest {

    @Test
    fun `accessibility configs request view events but no touch interception mode`() {
        listOf(
            "app/src/googleServices/res/xml/interaction_accessibility_service_config.xml",
            "app/src/googleServices/res/xml-v31/interaction_accessibility_service_config.xml",
        ).forEach { relativePath ->
            val source = String(Files.readAllBytes(repoPath(relativePath)))
            assertTrue(source.contains("android:accessibilityFlags=\"flagDefault\""))
            assertFalse(source.contains("flagRequestTouchExplorationMode", ignoreCase = true))
            assertFalse(source.contains("flagSendMotionEvents", ignoreCase = true))
            assertFalse(source.contains("motionEventSources", ignoreCase = true))
        }
    }

    @Test
    fun `collector never subscribes to or handles the touchscreen MotionEvent stream`() {
        val source = String(
            Files.readAllBytes(
                repoPath(
                    "app/src/googleServices/java/com/openlattice/chronicle/collection/interaction/" +
                        "InteractionCollectionService.kt",
                ),
            ),
        )
        assertFalse(source.contains("setMotionEventSources"))
        assertFalse(source.contains("override fun onMotionEvent"))
        assertFalse(source.contains("TouchInteractionController"))
    }

    @Test
    fun `new interaction rows preserve node bounds without fake raw tap coordinates`() {
        val source = String(
            Files.readAllBytes(
                repoPath(
                    "app/src/googleServices/java/com/openlattice/chronicle/collection/interaction/" +
                        "InteractionCollectionService.kt",
                ),
            ),
        )
        assertTrue(source.contains("nodeBoundsLeft = bounds.left.takeIf { captureElementPosition }"))
        assertTrue(source.contains("nodeBoundsTop = bounds.top.takeIf { captureElementPosition }"))
        assertTrue(source.contains("nodeBoundsRight = bounds.right.takeIf { captureElementPosition }"))
        assertTrue(source.contains("nodeBoundsBottom = bounds.bottom.takeIf { captureElementPosition }"))
        assertTrue(source.contains("rawX = null"))
        assertTrue(source.contains("rawY = null"))
        assertTrue(source.contains("normalizedX = null"))
        assertTrue(source.contains("normalizedY = null"))
    }

    private fun repoPath(relativePath: String): Path {
        val candidates = listOf(
            Paths.get(relativePath),
            Paths.get("chronicle").resolve(relativePath),
            Paths.get(relativePath.removePrefix("app/")),
        )
        return candidates.firstOrNull(Files::exists)
            ?: error("$relativePath not found from ${Paths.get("").toAbsolutePath()}")
    }
}
