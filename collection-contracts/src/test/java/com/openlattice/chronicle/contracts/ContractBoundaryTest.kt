package com.openlattice.chronicle.contracts

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Tranche 7 exit-criterion proof (shared-contracts plan, 08-rollout-sequence.md):
 * contract-facing code must not require Android storage imports.
 *
 * `:collection-contracts` is the pure collection-contract consumption layer. Every
 * source file in it — and the module's own dependency declarations — must stay free
 * of the Android storage/runtime stack that remains in `:collection-base`:
 * Room, SQLCipher, androidx.sqlite, and WorkManager.
 */
class ContractBoundaryTest {

    /** Import prefixes that belong to the Android storage/runtime layer, never the contract layer. */
    private val forbiddenImportPrefixes = listOf(
        "androidx.room",
        "net.zetetic",
        "net.sqlcipher",
        "androidx.sqlite",
        "androidx.work",
        // The storage layer's own package must not leak back into the contract layer.
        "com.openlattice.chronicle.storage",
    )

    /** Dependency coordinates (group or group:name fragments) forbidden in this module's build file. */
    private val forbiddenDependencyFragments = listOf(
        "androidx.room",
        "net.zetetic",
        "net.sqlcipher",
        "androidx.sqlite",
        "androidx.work",
    )

    private fun moduleDir(): File {
        // Unit-test workers run with user.dir at the module directory; walk up defensively
        // in case a future Gradle version roots the worker at the build directory.
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java/com/openlattice/chronicle").isDirectory &&
                File(dir, "build.gradle").isFile
            ) {
                return dir
            }
            val candidate = File(dir, "collection-contracts")
            if (File(candidate, "build.gradle").isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        fail("Could not locate the collection-contracts module directory from ${System.getProperty("user.dir")}")
        throw AssertionError("unreachable")
    }

    @Test
    fun `contract sources import no Room, SQLCipher, sqlite, WorkManager, or storage-layer classes`() {
        val mainSrc = File(moduleDir(), "src/main/java")
        val sources = mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Expected contract sources under $mainSrc", sources.isNotEmpty())

        val violations = mutableListOf<String>()
        for (file in sources) {
            file.readLines().forEachIndexed { index, raw ->
                val line = raw.trim()
                // Comment lines don't count; code that references a forbidden package
                // does — whether through an import or a fully-qualified inline name
                // (e.g. androidx.room.Room.databaseBuilder(...) with no import at all).
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                    return@forEachIndexed
                }
                val code = line.substringBefore("//")
                if (line.startsWith("import ")) {
                    val imported = line.removePrefix("import ").trim()
                    for (prefix in forbiddenImportPrefixes) {
                        if (imported == prefix || imported.startsWith("$prefix.")) {
                            violations += "${file.relativeTo(moduleDir())}:${index + 1}: $line"
                        }
                    }
                } else {
                    for (prefix in forbiddenImportPrefixes) {
                        if (code.contains("$prefix.")) {
                            violations += "${file.relativeTo(moduleDir())}:${index + 1}: $line"
                        }
                    }
                }
            }
        }
        assertTrue(
            "Contract layer must not import or reference Android storage/runtime classes:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `contract build configuration declares no Room, SQLCipher, sqlite, or WorkManager dependencies`() {
        val module = moduleDir()
        val androidRoot = requireNotNull(module.parentFile) { "Contract module must have an Android project parent" }
        val buildFiles = listOf(
            File(module, "build.gradle"),
            File(androidRoot, "gradle/collection-library.gradle"),
        )
        buildFiles.forEach { assertTrue("Expected build file at $it", it.isFile) }
        val violations = mutableListOf<String>()
        for (buildFile in buildFiles) {
            buildFile.readLines().forEachIndexed { index, raw ->
                val line = raw.trim()
                // Only dependency declarations count; comments documenting the boundary do not.
                if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                val isDependencyLine = Regex(
                    "^(api|implementation|compileOnly|runtimeOnly|ksp|kapt|annotationProcessor)\\b"
                ).containsMatchIn(line)
                if (!isDependencyLine) return@forEachIndexed
                for (fragment in forbiddenDependencyFragments) {
                    if (line.contains(fragment)) {
                        violations += "${buildFile.relativeTo(androidRoot)}:${index + 1}: $line"
                    }
                }
            }
        }
        assertTrue(
            "Contract module must not depend on Android storage/runtime artifacts:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
