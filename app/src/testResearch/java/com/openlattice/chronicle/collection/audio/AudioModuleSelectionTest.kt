package com.openlattice.chronicle.collection.audio

import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioModuleSelectionTest {
    @Test
    fun audioContentRemainsCollectableWhenAudioActivityIsOff() {
        val modules = audioModulesToCapture { module -> module == CollectionModuleId.AUDIO_CONTENT }

        assertEquals(setOf(CollectionModuleId.AUDIO_CONTENT), modules)
    }

    @Test
    fun independentlyAcceptedAudioModulesAreSelectedExactly() {
        val accepted = setOf(CollectionModuleId.AUDIO_ACTIVITY, CollectionModuleId.AUDIO_CONTENT)

        assertEquals(accepted, audioModulesToCapture(accepted::contains))
        assertEquals(emptySet<CollectionModuleId>(), audioModulesToCapture { false })
    }
}
