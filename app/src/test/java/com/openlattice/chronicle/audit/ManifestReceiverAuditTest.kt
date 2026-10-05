package com.openlattice.chronicle.audit

import com.openlattice.chronicle.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestReceiverAuditTest {
    @Test fun mergedManifestHasOnlyUsefulNotificationAndBootReceivers() {
        val variant=BuildConfig.DISTRIBUTION_CHANNEL.lowercase()+"Debug"
        val base=listOf(File("build/intermediates/merged_manifest"),File("app/build/intermediates/merged_manifest")).first{it.exists()}
        val manifest=base.walkTopDown().first{it.name=="AndroidManifest.xml"&&it.path.contains("/$variant/")}
        val document=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(manifest)
        val receivers=document.getElementsByTagName("receiver")
        val names=(0 until receivers.length).map{(receivers.item(it) as org.w3c.dom.Element).getAttributeNS("http://schemas.android.com/apk/res/android","name")}
        assertFalse(names.any{it.endsWith("NotificationPermissionReceiver")||it.endsWith("NotificationPermissionListener")})
        assertTrue(names.any{it.endsWith("StartOnBoot")})
        if(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)assertTrue(names.any{it.endsWith("LockedBootReceiver")})
    }
}
