package com.openlattice.chronicle.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.checkLocalStoreWrite
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.openlattice.chronicle.services.upload.UploadTransport
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.WeakHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The CA root an invitation link carries (`serverCa`, base64url DER) for a self-host server
 * whose certificate comes from the bundle's own private CA (the local HTTPS trial). The root
 * becomes an extra trust anchor for that one server origin, so the phone needs no CA profile.
 * A server without a stored root is evaluated exactly as before.
 */
object InvitedServerCa {
    private const val TAG = "InvitedServerCa"
    private const val PREFERENCES = "invited_server_ca"
    // A 4096-bit RSA root is about 1.4 KB of DER; anything far larger is not a CA root.
    private const val MAX_PARAMETER_CHARS = 4_096

    @Volatile private var preferences: SharedPreferences? = null
    private val clients = WeakHashMap<OkHttpClient, Unit>()
    /**
     * The trust generation before any invitation of this process changed trust. Clearing at it retires only what an
     * earlier process stored, such as the root of an invitation whose process died mid-wizard.
     */
    const val INHERITED_TRUST_GENERATION = 0L
    @Volatile private var generation = INHERITED_TRUST_GENERATION

    /**
     * Makes stored roots readable to every client the process builds; Application.onCreate calls
     * it. The root is public and uploads can run before first unlock, so device-protected storage.
     */
    @Synchronized fun attach(context: Context): SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .also { preferences = it }

    /** The link parameter as a CA certificate, or null when it is not exactly that. */
    fun parse(parameter: String): X509Certificate? {
        if (parameter.isEmpty() || parameter.length > MAX_PARAMETER_CHARS) return null
        val der = runCatching { Base64.getUrlDecoder().decode(parameter) }.getOrNull() ?: return null
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(der) != parameter) return null
        val certificate = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        }.getOrNull() ?: return null
        // Reject trailing objects, intermediates and end-entity certificates at the link boundary.
        if (!certificate.encoded.contentEquals(der) || certificate.basicConstraints < 0 ||
            certificate.subjectX500Principal != certificate.issuerX500Principal) return null
        return runCatching { certificate.verify(certificate.publicKey); certificate }.getOrNull()
    }

    /**
     * Only LAN servers take a link root. The bundle's private CA exists only in the LAN trial,
     * and a link must never add an anchor for a public host that a stranger could be serving.
     */
    fun acceptsOrigin(origin: String): Boolean {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.orEmpty() in listOf("", "/") && (uri.port == -1 || uri.port in 1..65535) &&
            isLanHost(uri.host ?: return false)
    }

    /**
     * A new invitation replaces all previous trust, including when it carries no root. [origin] is a normalized
     * trusted server URL (Utils.normalizeTrustedServerUrl).
     */
    @Synchronized fun replace(context: Context, origin: String? = null, root: X509Certificate? = null): Long {
        val parameter = root?.let {
            require(origin != null && acceptsOrigin(origin)) { "An invitation CA applies only to a LAN server" }
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.encoded).also { encoded ->
                require(parse(encoded) != null) { "An invitation CA must be a self-signed root" }
            }
        }
        // Withdraw in-memory trust before touching disk; failed writes must also fail closed.
        preferences = null
        generation++
        retireClients()
        val committed = try {
            val stored = attach(context)
            preferences = null
            val editor = stored.edit().clear()
            if (parameter != null) editor.putString(requireNotNull(origin), parameter)
            editor.commit().also { if (it) preferences = stored }
        } catch (error: IllegalStateException) {
            preferences = null
            throw LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED, error)
        }
        checkLocalStoreWrite(committed) { "Unable to persist invitation CA" }
        return generation
    }

    @Synchronized fun clear(context: Context, expectedGeneration: Long? = null) {
        if (expectedGeneration == null || expectedGeneration == generation) replace(context)
    }

    /** Tests stand in for a new process: nothing done so far owns the stored roots. */
    @VisibleForTesting @Synchronized internal fun simulateProcessStart() {
        generation = INHERITED_TRUST_GENERATION
    }

    /** Editing the server address cannot carry trust from the previous origin into enrollment. */
    @Synchronized fun retainOrigin(context: Context, origin: String) {
        if (preferences?.all?.keys?.any { it != origin } == true) clear(context)
    }

    /**
     * Commits the pending row's removal, then revokes its trust; never the reverse, which could leave a live attempt
     * without the root its retry and cancellation need. Process death or a failed write after the removal leaves only an
     * orphan root, already withdrawn in memory, that EnrollmentRecoveryManager.resumeIfNeeded retires on the next start.
     * The attempt has ended either way, so a failed revocation does not fail the removal. A newer invitation's trust is
     * left alone.
     */
    internal fun clearAfterPendingRemoval(context: Context, remove: () -> Boolean): Boolean {
        val owned = generation
        if (!remove()) return false
        try {
            clear(context, owned)
        } catch (error: LocalStoreRecoveryRequiredException) {
            Log.w(TAG, "Invitation CA revocation deferred to the next start (${error.recoveryReason})")
        }
        return true
    }

    private fun retireClients() {
        clients.keys.forEach { client ->
            // UploadTransport's two clients share this dispatcher and pool.
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    /** Cached Retrofit APIs resolve a fresh client after every trust change. */
    internal class Transport(private val origin: String, private val base: OkHttpClient) : Call.Factory {
        private var clientGeneration = -1L
        private var currentTransport: UploadTransport? = null

        fun current(): UploadTransport = synchronized(InvitedServerCa) {
            if (clientGeneration != generation) {
                val revision = generation
                val root = rootFor(origin)
                val url = origin.toHttpUrl()
                val guard = Interceptor { chain ->
                    if (revision != generation) throw IOException("Invitation trust changed; retry with the current client")
                    val requested = chain.request().url
                    val sameOrigin = requested.scheme == url.scheme && requested.host == url.host && requested.port == url.port
                    if (root != null && !sameOrigin) {
                        throw IOException("An invitation CA applies only to its server origin")
                    }
                    chain.proceed(chain.request())
                }
                val trust = InvitedCaTrustManager(systemTrustManager) { root }
                val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
                val pool = ConnectionPool()
                val retirement = object : EventListener() {
                    override fun callEnd(call: Call) = evictRetired()
                    override fun callFailed(call: Call, ioe: IOException) = evictRetired()
                    private fun evictRetired() {
                        // evictAll skips active HTTP/2 connections until their last call releases them.
                        if (revision != generation) pool.evictAll()
                    }
                }
                val client = base.newBuilder().sslSocketFactory(tls.socketFactory, trust)
                    .connectionPool(pool).eventListener(retirement)
                    .addInterceptor(guard).addNetworkInterceptor(guard).build()
                clients[client] = Unit
                currentTransport = UploadTransport(client)
                clientGeneration = revision
            }
            checkNotNull(currentTransport)
        }

        override fun newCall(request: Request): Call = current().newCall(request)
    }

    @Synchronized fun rootFor(origin: String): X509Certificate? =
        if (acceptsOrigin(origin)) preferences?.getString(origin, null)?.let(::parse) else null

    /** The platform trust manager, which applies the flavor's network-security-config. */
    val systemTrustManager: X509ExtendedTrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509ExtendedTrustManager>().single()
    }
}

/** IP literals in loopback, private and link-local ranges, `localhost` and mDNS `.local` names. */
internal fun isLanHost(host: String): Boolean {
    val name = host.lowercase(Locale.ROOT)
    return when {
        name == "localhost" || name.endsWith(".local") -> true
        name.startsWith("[") -> isLanIpv6(name.removePrefix("[").removeSuffix("]"))
        else -> isLanIpv4(name)
    }
}

/** Loopback, link-local (fe80::/10) and unique-local (fc00::/7). */
private fun isLanIpv6(literal: String): Boolean {
    // java.net.URI has already validated a bracketed host, so this parses without DNS.
    val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
    return address is Inet6Address && isLanAddress(address)
}

/** 10/8, 127/8, 172.16/12, 192.168/16 and 169.254/16, as a strict dotted quad. */
private fun isLanIpv4(name: String): Boolean {
    val octets = name.split('.').mapNotNull { part -> part.toIntOrNull()?.takeIf { part == it.toString() && it in 0..255 } }
    if (octets.size != 4 || name.count { it == '.' } != 3) return false
    val (a, b) = octets
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
}

/** Check the connected peer too: DNS names in the local namespace can still resolve publicly. */
private fun isLanAddress(address: InetAddress): Boolean {
    if (address is Inet6Address) {
        val first = address.address[0].toInt() and 0xff
        return address.isLoopbackAddress || address.isLinkLocalAddress || (first and 0xfe) == 0xfc
    }
    return isLanIpv4(address.hostAddress ?: return false)
}

/** The server's certificate chain or hostname was rejected, as opposed to an unreachable server. */
internal fun isCertificateRejection(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { it is CertificateException || it is SSLPeerUnverifiedException }

/**
 * System trust first, then the invitation root for this origin when one is stored. Every
 * variant delegates to the same-shaped system method, so per-domain network-security-config
 * rules keep working. OkHttp still verifies the hostname after the handshake.
 */
internal class InvitedCaTrustManager(
    private val system: X509ExtendedTrustManager,
    private val invitedRoot: () -> X509Certificate?,
) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        trust(chain, authType, socket?.inetAddress?.let(::isLanAddress) == true) {
            system.checkServerTrusted(chain, authType, socket)
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        system.checkServerTrusted(chain, authType, engine)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        system.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        system.checkClientTrusted(chain, authType, engine)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + listOfNotNull(invitedRoot())

    private inline fun trust(chain: Array<X509Certificate>, authType: String, localPeer: Boolean, systemCheck: () -> Unit) {
        val root = if (localPeer) invitedRoot() else null
        if (root == null) {
            systemCheck()
            return
        }
        try {
            systemCheck()
        } catch (systemRejection: CertificateException) {
            try {
                anchoredOn(root).checkServerTrusted(chain, authType)
            } catch (invitedRejection: CertificateException) {
                invitedRejection.addSuppressed(systemRejection)
                throw invitedRejection
            }
        }
    }

    private fun anchoredOn(root: X509Certificate): X509ExtendedTrustManager {
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("invited-root", root)
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(anchors) }
            .trustManagers.filterIsInstance<X509ExtendedTrustManager>().single()
    }
}
