package com.openlattice.chronicle.collection.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level invariant guarding the bug that shipped the six sensing-expansion modules
 * gated-but-not-acked (2026-06-19).
 *
 * Every module that has a consent-gate call site — `CollectionGate.collects(..., CollectionModuleId.X)`
 * or the holder helper `enrolledAndConsented(..., CollectionModuleId.X)` — MUST be in
 * [CollectionStateMachine.ACK_GATED_MODULES]. If it is not, `reconcile()` filters it out, so no
 * consent state is ever created, the gate can never open, and the module is silently inert:
 * invisible in the Data Sharing tab, no `NEEDS_DECISION` notification, and never collected even
 * when the study enables it.
 *
 * The sibling equality test in [CollectionStateMachineTest] only *snapshots* the set, so a new
 * call site that was never added to the set slips through (exactly what happened). This test reads
 * the real call sites from source, so the two cannot drift apart unnoticed.
 */
class CollectionGateCallSiteInvariantTest {

    private companion object {
        private val CALL_SITE = Regex(
            """(?:CollectionGate\.collects|enrolledAndConsented)\([^)]*CollectionModuleId\.([A-Z0-9_]+)""",
        )

        /** Modules scanned for and excluded because they gate on their own preference, not consent. */
        // (none today — user_identification's holder does NOT call CollectionGate.collects)

        fun sourceRoots(): List<File> {
            var dir: File? = File(".").absoluteFile
            repeat(6) {
                val base = dir
                if (base != null && File(base, "app/src/main").isDirectory) {
                    return listOf("app/src/main", "collection-core/src/main", "collection-device/src/main")
                        .map { File(base, it) }
                        .filter { it.isDirectory }
                }
                dir = base?.parentFile
            }
            return listOf(File("src/main")).filter { it.isDirectory }
        }
    }

    @Test
    fun everyConsentGateCallSiteModuleIsAckGated() {
        val callSiteModules = sourceRoots().asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> CALL_SITE.findAll(f.readText()).map { it.groupValues[1] } }
            .toSet()

        assertTrue(
            "Found no CollectionGate.collects / enrolledAndConsented call sites — the scan root is " +
                "wrong (cwd=${File(".").absolutePath}); the invariant would pass vacuously.",
            callSiteModules.isNotEmpty(),
        )

        val acked = CollectionStateMachine.ACK_GATED_MODULES.map { it.name }.toSet()
        val missing = (callSiteModules - acked).sorted()
        assertTrue(
            "These modules are consent-gated at a call site but are MISSING from " +
                "CollectionStateMachine.ACK_GATED_MODULES. reconcile() will filter them out, so they " +
                "never get a consent state — invisible in Data Sharing, no review notification, never " +
                "collected. Add them to ACK_GATED_MODULES: $missing",
            missing.isEmpty(),
        )
    }
}
