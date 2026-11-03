package com.openlattice.chronicle.security

import com.openlattice.chronicle.EnrollmentLink
import com.openlattice.chronicle.R
import com.openlattice.chronicle.parseEnrollmentLink
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.security.cert.CertificateException
import java.util.Base64
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** JVM only: the link parameter, the LAN-host rule and the rejection classifier. */
class InvitedServerCaTest {
    private val root = HeldCertificate.Builder().certificateAuthority(1).commonName("Trial Root").build()
    private val leaf = HeldCertificate.Builder().commonName("leaf").signedBy(root).build()

    @Test fun parsesTheBase64UrlDerOfACaRoot() {
        assertEquals(root.certificate, InvitedServerCa.parse(parameter(root)))
    }

    @Test fun refusesAnythingThatIsNotOneCaCertificate() {
        assertNull("end-entity certificate", InvitedServerCa.parse(parameter(leaf)))
        val intermediate = HeldCertificate.Builder().certificateAuthority(0).signedBy(root).build()
        assertNull("intermediate is not a self-signed root", InvitedServerCa.parse(parameter(intermediate)))
        assertNull("trailing certificate", InvitedServerCa.parse(Base64.getUrlEncoder().withoutPadding()
            .encodeToString(root.certificate.encoded + leaf.certificate.encoded)))
        assertNull("padded base64", InvitedServerCa.parse(parameter(root) + "="))
        assertNull("empty", InvitedServerCa.parse(""))
        assertNull("not base64url", InvitedServerCa.parse("not+base64/url="))
        assertNull("not a certificate", InvitedServerCa.parse(Base64.getUrlEncoder().encodeToString("hello".toByteArray())))
        assertNull("oversized", InvitedServerCa.parse("A".repeat(4_097)))
    }

    @Test fun acceptsOnlyLanOrigins() {
        listOf(
            "https://192.168.1.50:8443", "https://10.0.0.2", "https://172.16.0.1", "https://172.31.255.254",
            "https://127.0.0.1:443", "https://169.254.3.4", "https://localhost:9443", "https://lab-mac.local",
            "https://[fd00::5]:8443", "https://[fe80::1]", "https://[::1]",
        ).forEach { assertTrue(it, InvitedServerCa.acceptsOrigin(it)) }
        listOf(
            "https://chronicle.example.org", "https://8.8.8.8", "https://172.32.0.1", "https://192.169.1.1",
            "https://11.0.0.1", "https://[2001:db8::1]", "http://192.168.1.50", "https://192.168.1",
            "https://192.168.1.500", "https://local.example.org", "not a url",
            "https://010.0.0.1", "https://0127.0.0.1", "https://2130706433", "https://0x7f000001",
            "https://0.0.0.0", "https://[::ffff:8.8.8.8]", "https://[::ffff:127.0.0.1]",
            "https://192.168.1.50.", "https://user@localhost", "https://localhost:0", "https://localhost:65536",
            "https://localhost/path", "https://localhost?other", "https://localhost#other",
            "https://xn--localhost-9za.example", "https://lab.local.example",
        ).forEach { assertFalse(it, InvitedServerCa.acceptsOrigin(it)) }
    }

    @Test fun anInvitationLinkCarriesTheRootForALanServer() {
        val parsed = parseEnrollmentLink(link("https://localhost:8443", parameter(root)), CODE)
        assertTrue(parsed is EnrollmentLink.Prefill)
        assertEquals(root.certificate, (parsed as EnrollmentLink.Prefill).serverCa)
    }

    @Test fun aLinkWithoutTheParameterCarriesNoRoot() {
        val parsed = parseEnrollmentLink(link("https://localhost:8443", null), CODE) as EnrollmentLink.Prefill
        assertNull(parsed.serverCa)
    }

    @Test fun aMalformedOrMisplacedRootRejectsTheWholeLink() {
        listOf(
            link("https://localhost:8443", parameter(leaf)),
            link("https://localhost:8443", "garbage"),
            link("https://localhost:8443", ""),
        ).forEach { uri ->
            val parsed = parseEnrollmentLink(uri, CODE)
            assertEquals(uri, R.string.enrollment_invitation_unverified, (parsed as EnrollmentLink.Invalid).message)
        }
    }

    @Test fun classifiesCertificateAndHostnameRejectionsApartFromNetworkFailures() {
        assertTrue(isCertificateRejection(SSLHandshakeException("chain").apply { initCause(CertificateException("untrusted")) }))
        assertTrue(isCertificateRejection(SSLPeerUnverifiedException("Hostname 10.0.0.2 not verified")))
        assertFalse(isCertificateRejection(SSLHandshakeException("connection reset")))
        assertFalse(isCertificateRejection(java.net.ConnectException("refused")))
    }

    private fun parameter(certificate: HeldCertificate) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(certificate.certificate.encoded)

    private fun link(serverUrl: String, serverCa: String?) = buildString {
        append("chronicle://enroll?studyId=11111111-1111-1111-1111-111111111111&participantId=participant")
        append("&serverUrl=").append(URLEncoder.encode(serverUrl, "UTF-8"))
        if (serverCa != null) append("&serverCa=").append(serverCa)
    }

    private companion object {
        val CODE = "a".repeat(40)
    }
}
