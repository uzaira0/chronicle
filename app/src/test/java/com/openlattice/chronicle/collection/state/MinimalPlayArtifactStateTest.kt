package com.openlattice.chronicle.collection.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalPlayArtifactStateTest {
    @Test
    fun playRequiresCleanupAndPolicyProofForExactRegistry() {
        assertFalse(minimalPlayArtifactReady("PLAY", "new", null, null))
        assertFalse(minimalPlayArtifactReady("PLAY", "new", "old", "new"))
        assertFalse(minimalPlayArtifactReady("PLAY", "new", "new", "old"))
        assertFalse(
            minimalPlayArtifactReady(
                "PLAY",
                "new",
                "new",
                "new",
                runtimePolicyClosed = true,
            ),
        )
        assertTrue(minimalPlayArtifactReady("PLAY", "new", "new", "new"))
    }

    @Test
    fun nonPlayDistributionsDoNotDependOnPlayBoundaryState() {
        assertTrue(minimalPlayArtifactReady("RESEARCH", "", null, null))
        assertTrue(minimalPlayArtifactReady("OPEN", "registry", "old", null))
    }

    @Test
    fun amazonRequiresTheSameMinimalPublicProofs() {
        assertFalse(minimalPlayArtifactReady("AMAZON", "registry", null, null))
        assertTrue(minimalPlayArtifactReady("AMAZON", "registry", "registry", "registry"))
    }
}
