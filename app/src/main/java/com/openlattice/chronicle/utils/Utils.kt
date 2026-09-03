@file:Suppress("DEPRECATION")

package com.openlattice.chronicle.utils

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.R
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.security.MobileApiSigningInterceptor
import com.openlattice.chronicle.services.notifications.CHANNEL_ID
import com.openlattice.chronicle.services.notifications.NotificationDetails
import com.openlattice.chronicle.serialization.ChronicleCallAdapterFactory
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.upload.LAST_UPDATED_SETTING
import com.openlattice.chronicle.services.upload.LAST_UPLOADED_PLACEHOLDER
import com.openlattice.chronicle.services.upload.LATEST_TIMESTAMP_UPLOADED_SETTING
import com.openlattice.chronicle.services.upload.UPLOAD_QUEUE_SIZE_SETTING
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object Utils {
    private const val TAG = "ChronicleUtils"

    // Controlled research builds may restrict enrollment to one operator-configured host.
    // Public builds leave this blank and accept any valid system-trusted HTTPS origin.
    private val CHRONICLE_PRODUCTION_HOST = BuildConfig.CHRONICLE_PRODUCTION_HOST.lowercase(Locale.ROOT)
    private val TRUSTED_SERVER_HOSTS = buildSet {
        if (CHRONICLE_PRODUCTION_HOST.isNotBlank()) add(CHRONICLE_PRODUCTION_HOST)
    }

    fun offsetDateTimeFromEpochMillis( epochMillis: Long ) : OffsetDateTime {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)
    }

    private fun formatAsMediumDateTime(isoDateTime: String): String {
        val parsed = runCatching { OffsetDateTime.parse(isoDateTime) }.getOrElse { e ->
            // Backward/defensive: if parsing fails, return original string
            Log.w(TAG, "Unable to parse stored timestamp", e)
            return isoDateTime
        }

        // Match Joda's mediumDateTime() intent using device locale + timezone.
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        return parsed.atZoneSameInstant(ZoneId.systemDefault()).format(formatter)
    }

    /**
     * Canonicalizes and authorizes an upload server URL before it is used for enrollment,
     * health checks, Retrofit construction, or upload. Public distribution flavors accept any
     * hostname over standard HTTPS; controlled research builds retain the BCM allowlist and
     * certificate pin. Debug builds may additionally use local development servers.
     */
    @JvmStatic
    fun normalizeTrustedServerUrl(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.rawPath.orEmpty()
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port
        val validHttpsPort = port == -1 || port in 1..65535

        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (path.isNotEmpty() && path != "/") return null
        // Public self-host-capable builds trust ANY valid https host (still https-only, still no
        // userinfo/query/fragment/path). Controlled builds keep the fixed allowlist below.
        val anyServer = BuildConfig.ALLOW_ANY_SERVER &&
            scheme == "https" && host.isNotBlank() && validHttpsPort
        val trusted = anyServer ||
            (scheme == "https" && host in TRUSTED_SERVER_HOSTS && (port == -1 || port == defaultPort))
        val debugLocal = BuildConfig.DEBUG &&
            (host == "localhost" || host == "127.0.0.1" || host.endsWith(".local")) &&
            (scheme == "https" || scheme == "http")
        if (!trusted && !debugLocal) return null

        return buildString {
            append(scheme)
            append("://")
            append(host)
            if (port != -1 && port != defaultPort) {
                append(":")
                append(port)
            }
        }
    }

    /** Returns true only for trusted URLs accepted by enrollment/server configuration. */
    @JvmStatic
    fun requireHttpsUrl(url: String): Boolean {
        return normalizeTrustedServerUrl(url) != null
    }

    private val SHA256_PROTOTYPE: MessageDigest = MessageDigest.getInstance("SHA-256")

    /**
     * Returns a SHA-256 hash of the input string, truncated to the first 16 hex characters.
     * Used to avoid sending plaintext participant IDs to third-party analytics services.
     */
    @JvmStatic
    fun hashForAnalytics(value: String): String {
        val digest = SHA256_PROTOTYPE.clone() as MessageDigest
        val hash = digest.digest(value.toByteArray())
        return buildString(16) {
            for (i in 0 until 8) {
                append(String.format("%02x", hash[i]))
            }
        }
    }

    fun isValidUUID(possibleUUID: String): Boolean {
        try {
            if (possibleUUID.isEmpty()) {
                return false
            }
            UUID.fromString(possibleUUID)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "Invalid UUID input")
            return false
        }
        return true
    }

    // Delegates to the R/BuildConfig-free :collection-base helper so the label-lookup
    // logic has a single home; behaviour is unchanged.
    fun getAppFullName(context: Context, packageName: String): String =
        com.openlattice.chronicle.utils.PackageLabels.getAppFullName(context, packageName)

    // Return true if job service is running. In API 24 the solution would be: scheduler.getPendingJob(JOB_ID) != null
    fun isJobServiceScheduled(context: Context, jobId: Number): Boolean {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        return jobScheduler.allPendingJobs.any { it.id == jobId }
    }

    fun createNotificationTargetUrl(
        notificationDetails: NotificationDetails,
        studyId: String,
        participantId: String
    ): String {
        val trustedServer = normalizeTrustedServerUrl(
            requireNotNull(notificationDetails.serverUrl) { "Reminder server is required" },
        ) ?: throw IllegalArgumentException("Reminder server is not trusted")
        val accessCode = requireNotNull(notificationDetails.accessCode?.takeIf { it.isNotBlank() }) {
            "Participant access code is required"
        }
        val formPath = when (notificationDetails.type) {
            NotificationType.QUESTIONNAIRE -> "questionnaire"
            NotificationType.AWARENESS -> "survey"
        }
        val uriBuilder = Uri.parse("$trustedServer/chronicle/$formPath").buildUpon()

        if (notificationDetails.type === NotificationType.QUESTIONNAIRE) {
            uriBuilder
                .appendQueryParameter("studyId", studyId)
                .appendQueryParameter("participantId", participantId)
                .appendQueryParameter("questionnaireId", notificationDetails.id)
        }
        if (notificationDetails.type === NotificationType.AWARENESS) {
            uriBuilder
                .appendQueryParameter("studyId", studyId)
                .appendQueryParameter("participantId", participantId)
        }

        uriBuilder.encodedFragment("accessCode=${Uri.encode(accessCode)}")

        return uriBuilder.build().toString()
    }


    fun updateUploadInfo(context: Context, latestTimestampUploaded: OffsetDateTime?) {
        val settings = EncryptedPrefsHelper.getEncryptedPrefs(context)
        with(settings.edit()) {
            putString(LAST_UPDATED_SETTING, OffsetDateTime.now(ZoneOffset.UTC).toString())
            if (latestTimestampUploaded != null) {
                putString(LATEST_TIMESTAMP_UPLOADED_SETTING, latestTimestampUploaded.toString())
            }
            apply()
        }
    }

    fun getLastUpload(context: Context): String {

        val settings = EncryptedPrefsHelper.getEncryptedPrefs(context)
        val lastUpdated =
            settings.getString(LAST_UPDATED_SETTING, LAST_UPLOADED_PLACEHOLDER)
                ?: LAST_UPLOADED_PLACEHOLDER

        if (lastUpdated != LAST_UPLOADED_PLACEHOLDER) {
            return formatAsMediumDateTime(lastUpdated)
        }

        return lastUpdated
    }

    fun getLatestTimestampUploaded(context: Context): String {
        val settings = EncryptedPrefsHelper.getEncryptedPrefs(context)
        val latestTimestampUploaded =
            settings.getString(LATEST_TIMESTAMP_UPLOADED_SETTING, LAST_UPLOADED_PLACEHOLDER)
                ?: LAST_UPLOADED_PLACEHOLDER

        if (latestTimestampUploaded != LAST_UPLOADED_PLACEHOLDER) {
            return formatAsMediumDateTime(latestTimestampUploaded)
        }

        return latestTimestampUploaded
    }

    fun updateUploadQueueSize(context: Context, queueSize: Int) {
        val settings = EncryptedPrefsHelper.getEncryptedPrefs(context)
        with(settings.edit()) {
            putInt(UPLOAD_QUEUE_SIZE_SETTING, queueSize)
            apply()
        }
    }

    fun getUploadQueueSize(context: Context): Int {
        val settings = EncryptedPrefsHelper.getEncryptedPrefs(context)
        return settings.getInt(UPLOAD_QUEUE_SIZE_SETTING, 0)
    }

    fun createRetrofitAdapter(
        baseUrl: String,
        mobileSigningSecretOverride: String? = null
    ): Retrofit {
        val trustedBaseUrl = normalizeTrustedServerUrl(baseUrl)
            ?: throw IllegalArgumentException("Untrusted Chronicle server URL")
        val httpClientBuilder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Enrollment codes, per-device API keys, and participant identifiers use custom
            // headers that OkHttp does not strip on every cross-origin redirect. Fail closed on
            // all redirects so an enrolled server can never forward a request or credential to
            // a different origin or downgrade it to cleartext HTTP.
            .followRedirects(false)
            .followSslRedirects(false)
            // The server resolves its message table from Accept-Language; this header is not
            // part of the mobile signature canonical string.
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Accept-Language", Locale.getDefault().toLanguageTag())
                        .build()
                )
            }
        // Every supported public endpoint uses Android's system trust store. Research builds
        // may restrict the hostname above, but no tenant certificate or infrastructure name is
        // embedded in the distributed client.
        val mobileSigningSecret = effectiveMobileSigningSecret(mobileSigningSecretOverride)
        if (mobileSigningSecret.isNotBlank()) {
            httpClientBuilder.addInterceptor(
                MobileApiSigningInterceptor(mobileSigningSecret)
            )
        }
        val httpClient = httpClientBuilder.build()
        return Retrofit.Builder()
            .baseUrl("$trustedBaseUrl/")
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(ChronicleJson.moshi).withNullSerialization())
            .addCallAdapterFactory(ChronicleCallAdapterFactory())
            .build()
    }

    @JvmStatic
    fun effectiveMobileSigningSecret(mobileSigningSecretOverride: String?): String {
        return mobileSigningSecretOverride?.takeIf { it.isNotBlank() }
            ?: BuildConfig.MOBILE_SIGNING_SECRET
    }

    @JvmStatic
    fun mobileSigningSecretFingerprint(mobileSigningSecretOverride: String?): String {
        val secret = effectiveMobileSigningSecret(mobileSigningSecretOverride)
        if (secret.isBlank()) return "blank"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }

    // required by android 8.0 and higher.
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.resources.getString(R.string.channel_name)
            val channelDescription =
                context.resources.getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = channelDescription
            }
            //register channel
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    fun getPendingIntentMutabilityFlag(flags: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or flags
        } else {
            flags
        }
    }
}
