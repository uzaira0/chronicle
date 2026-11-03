package com.openlattice.chronicle.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.utils.Utils
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.mockwebserver.SocketPolicy
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Real TLS handshakes through the production client: a server whose certificate chains to a
 * private trial root (as Caddy's local CA does: root, intermediate, leaf).
 */
@RunWith(RobolectricTestRunner::class)
class InvitedServerCaHandshakeTest {
    private val trialRoot = HeldCertificate.Builder().certificateAuthority(1).commonName("Trial Root").build()
    private val otherRoot = HeldCertificate.Builder().certificateAuthority(1).commonName("Other Root").build()
    private val intermediate = HeldCertificate.Builder().certificateAuthority(0).signedBy(trialRoot).build()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val servers = mutableListOf<MockWebServer>()
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = serve(leafFor("localhost"))
    }

    @After fun stop() {
        servers.forEach(MockWebServer::shutdown)
    }

    @Test fun theInvitedRootLetsThePhoneTrustTheTrialServer() {
        InvitedServerCa.replace(context, origin(), trialRoot.certificate)
        assertEquals(200, get().code)
    }

    @Test fun aDifferentInvitedRootIsRejectedAsACertificateFailure() {
        InvitedServerCa.replace(context, origin(), otherRoot.certificate)
        val failure = assertThrows(IOException::class.java) { get() }
        assertTrue(isCertificateRejection(failure))
    }

    @Test fun withoutAnInvitedRootTheSystemStoreAloneDecides() {
        assertNull(InvitedServerCa.rootFor(origin()))
        val failure = assertThrows(IOException::class.java) { get() }
        assertTrue(isCertificateRejection(failure))
    }

    @Test fun theRootIsBoundToItsOriginOnly() {
        InvitedServerCa.replace(context, "https://localhost:${server.port + 1}", trialRoot.certificate)
        InvitedServerCa.replace(context, "https://127.0.0.1:${server.port}", trialRoot.certificate)
        assertNull(InvitedServerCa.rootFor(origin()))
        val failure = assertThrows(IOException::class.java) { get() }
        assertTrue(isCertificateRejection(failure))
    }

    @Test fun theInvitedRootNeverExcusesAHostnameMismatch() {
        server = serve(leafFor("chronicle.example.org"))
        InvitedServerCa.replace(context, origin(), trialRoot.certificate)
        assertThrows(SSLPeerUnverifiedException::class.java) { get() }
    }

    @Test fun anInvitationRootIsRefusedForAPublicServer() {
        assertThrows(IllegalArgumentException::class.java) {
            InvitedServerCa.replace(context, "https://chronicle.example.org", trialRoot.certificate)
        }
    }

    @Test fun aClientCannotUseItsRootForAnotherOrigin() {
        InvitedServerCa.replace(context, origin(), trialRoot.certificate)
        val another = serve(leafFor("localhost"))
        assertThrows(IOException::class.java) {
            client().newCall(Request.Builder().url("https://localhost:${another.port}/").build()).execute().close()
        }
    }

    @Test fun theChainCleanerCanBuildTheInvitedChain() {
        InvitedServerCa.replace(context, origin(), trialRoot.certificate)
        val secured = client().newBuilder().certificatePinner(okhttp3.CertificatePinner.Builder()
            .add("localhost", okhttp3.CertificatePinner.pin(trialRoot.certificate)).build()).build()
        secured.newCall(Request.Builder().url("${origin()}/").build()).execute().use { assertEquals(200, it.code) }
    }

    @Test fun clearingTrustRetiresAnAuthenticatedConnectionAndItsTlsSession() {
        InvitedServerCa.replace(context, origin(), trialRoot.certificate)
        val factory = Utils.createRetrofitAdapter(origin()).callFactory() as InvitedServerCa.Transport
        val cached = factory.current().interactiveClient
        val request = Request.Builder().url("${origin()}/").build()
        cached.newCall(request).execute().close()
        server.enqueue(MockResponse().setBody("reused"))
        cached.newCall(request).execute().close()
        assertEquals(0, server.takeRequest().sequenceNumber)
        assertEquals(1, server.takeRequest().sequenceNumber)
        assertEquals(1, cached.connectionPool.idleConnectionCount())

        InvitedServerCa.clear(context)

        assertEquals(0, cached.connectionPool.connectionCount())
        assertNull(InvitedServerCa.rootFor(origin()))
        server.enqueue(MockResponse().setBody("revoked trust must not send this request"))
        assertThrows(IOException::class.java) { cached.newCall(request).execute().close() }
        val failure = assertThrows(IOException::class.java) { factory.newCall(request).execute().close() }
        assertTrue(isCertificateRejection(failure))
        // The cached API remains usable after a new invitation installs its own root.
        InvitedServerCa.replace(context, origin(), trialRoot.certificate)
        server.enqueue(MockResponse().setBody("new enrollment"))
        factory.newCall(request).execute().use { assertEquals(200, it.code) }
        assertEquals(0, server.takeRequest().sequenceNumber)
    }

    @Test fun replacingTrustCancelsAnInFlightCallIncludingTheUploadClient() {
        InvitedServerCa.replace(context, origin(), trialRoot.certificate)
        val transport = (Utils.createRetrofitAdapter(origin()).callFactory() as InvitedServerCa.Transport).current()
        val request = Request.Builder().url("${origin()}/").build()
        transport.interactiveClient.newCall(request).execute().close()
        server.takeRequest()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val finished = CountDownLatch(1)
        val call = transport.collectedClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { finished.countDown() }
            override fun onResponse(call: Call, response: Response) { response.close(); finished.countDown() }
        })
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        assertFalse(call.isCanceled())

        InvitedServerCa.replace(context, origin(), otherRoot.certificate)

        assertTrue(call.isCanceled())
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(0, transport.collectedClient.connectionPool.connectionCount())
        assertThrows(IOException::class.java) { transport.interactiveClient.newCall(request).execute().close() }
    }

    @Test fun aLateCancellationCannotRevokeANewerInvitationsTrust() {
        val old = InvitedServerCa.replace(context, origin(), trialRoot.certificate)
        InvitedServerCa.replace(context, origin(), otherRoot.certificate)
        InvitedServerCa.clear(context, old)
        assertEquals(otherRoot.certificate, InvitedServerCa.rootFor(origin()))
    }

    @Test fun aLocalNameResolvingPubliclyCannotUseTheInvitedRoot() {
        val trust = InvitedCaTrustManager(InvitedServerCa.systemTrustManager) { trialRoot.certificate }
        val socket = object : Socket() {
            override fun getInetAddress(): InetAddress = InetAddress.getByName("8.8.8.8")
        }
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(leafFor("lab.local").certificate, intermediate.certificate), "RSA", socket)
        }
    }

    private fun leafFor(host: String) = HeldCertificate.Builder().addSubjectAlternativeName(host).signedBy(intermediate).build()

    private fun serve(leaf: HeldCertificate) = MockWebServer().apply {
        val certificates = HandshakeCertificates.Builder().heldCertificate(leaf, intermediate.certificate).build()
        useHttps(certificates.sslSocketFactory(), false)
        enqueue(MockResponse().setBody("ok"))
        start()
        servers += this
    }

    private fun origin() = "https://localhost:${server.port}"

    private fun get() = client().newCall(Request.Builder().url("${origin()}/").build()).execute().also { it.close() }

    private fun client(): OkHttpClient =
        (Utils.createRetrofitAdapter(origin()).callFactory() as InvitedServerCa.Transport).current().interactiveClient
}
