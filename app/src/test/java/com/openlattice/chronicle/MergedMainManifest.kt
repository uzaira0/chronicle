package com.openlattice.chronicle

import java.io.File
import com.openlattice.chronicle.audit.ManifestReceiverAuditTest
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/**
 * The merged main manifest the variant under test packages. The Android Gradle plugin hands Robolectric only the
 * unit-test manifest (no app components or application attributes), so Gradle supplies the main-manifest artifact:
 * tests that depend on a manifest declaration check it here instead of trusting Robolectric to have seen it.
 */
fun mergedMainManifest(): Document {
    val manifest = File(checkNotNull(System.getProperty(ManifestReceiverAuditTest::class.java.name)))
    check(manifest.isFile) { "merged manifest ${manifest.absolutePath}" }
    return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
}
