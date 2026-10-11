package com.openlattice.chronicle.services.upload

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.capability.CollectionCapabilityResolver
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.services.notifications.CHANNEL_ID
import com.openlattice.chronicle.collection.capability.PermissionStatus
import com.openlattice.chronicle.collection.capability.activeModulePermissionStatus
import com.openlattice.chronicle.services.notifications.postNotificationIfPermitted
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private const val ACCESS_TAG = "AccessLossDiagnostics"
private const val PREFS = "chronicle_access_missing_episodes"
private const val NOTIFICATION_ID_ACCESS = 47_004

internal fun clearAccessLossEpisodes(context: Context) {
    com.openlattice.chronicle.storage.checkLocalStoreWrite(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit())
}

/** Families whose accepted, ACTIVE module lacks the special Android access it collects through. */
internal fun missingAccessFamilies(status: PermissionStatus): Set<LocalUploadModuleFamily> = buildSet {
    if (status.needAccessibility) add(LocalUploadModuleFamily.INTERACTION)
    if (status.needNotificationListener) add(LocalUploadModuleFamily.NOTIFICATION)
    if (status.needUsageAccess) add(LocalUploadModuleFamily.USAGE_LIFECYCLE)
    if (status.needHealthConnect) add(LocalUploadModuleFamily.HEALTH)
    status.runtimeAccessModules.forEach { module ->
        when (module) {
            com.openlattice.chronicle.collection.CollectionModuleId.ACTIVITY_RECOGNITION -> add(LocalUploadModuleFamily.ACTIVITY_RECOGNITION)
            com.openlattice.chronicle.collection.CollectionModuleId.SLEEP -> add(LocalUploadModuleFamily.SLEEP)
            else -> add(LocalUploadModuleFamily.SENSOR)
        }
    }
}

/**
 * Records one COLLECTION_ACCESS_MISSING diagnostic per family per episode (for example the
 * accessibility service Android removes on force-stop) and returns the families that lost access
 * they had been granted, so the participant is told once. A family accepted but never granted (still
 * in onboarding) is reported to the server only. An episode ends when access returns; the next loss
 * is a new episode. The episode is persisted before it is recorded, and its stable ID makes a retry
 * after process death safe. [required] is every family an ACTIVE module needs access for.
 * [notifyInitiallyMissing] also notifies for a service that needs a foreground restart.
 */
internal fun recordAccessEpisodes(
    store: LocalUploadDiagnosticsStore,
    prefs: SharedPreferences,
    ownerScope: String,
    missing: Set<LocalUploadModuleFamily>,
    required: Set<LocalUploadModuleFamily>,
    now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    notifyInitiallyMissing: Set<LocalUploadModuleFamily> = emptySet(),
): Set<LocalUploadModuleFamily> {
    val prefix = "$ownerScope|"
    val grantedPrefix = "${prefix}granted|"
    val granted = (required - missing).mapTo(hashSetOf()) { grantedPrefix + it.name }
    val keep = missing.mapTo(hashSetOf()) { prefix + it.name } +
        required.map { grantedPrefix + it.name }.filter { it in granted || prefs.contains(it) }
    val stale = prefs.all.keys - keep
    if (stale.isNotEmpty()) check(prefs.edit().apply { stale.forEach(::remove) }.commit())
    val began = missing.filterTo(linkedSetOf()) { prefs.getString(prefix + it.name, null) == null }
    val lost = began.filterTo(linkedSetOf()) { it in notifyInitiallyMissing || prefs.contains(grantedPrefix + it.name) }
    if (began.isNotEmpty() || granted.isNotEmpty()) {
        check(prefs.edit().apply {
            began.forEach { putString(prefix + it.name, now.toString()) }
            granted.forEach { putString(it, "1") }
        }.commit())
    }
    missing.forEach { family ->
        val startedAt = prefs.getString(prefix + family.name, null) ?: return@forEach
        // nosemgrep: chronicle-uuid-from-string -- Access-loss episode key deduplicates repeated reports for this owner, family and start time.
        val id = UUID.nameUUIDFromBytes("access-missing:$prefix${family.name}:$startedAt".toByteArray()).toString()
        store.recordOperationalOnce(
            id, family, LocalOperationalIssue.COLLECTION_ACCESS_MISSING, OffsetDateTime.parse(startedAt),
            ownerScope = ownerScope,
        )
    }
    return lost
}

/** Reports missing Android access or a stopped sensor service for accepted modules. Never throws. */
fun recordCollectionAccessLoss(context: Context) {
    try {
        val owner = ResearchPersistenceGate.captureOwner(context) ?: return
        val states = CollectionLoopStore.of(context).loadAll().values.toList()
        val environment = CollectionCapabilityResolver.snapshot(context)
        val sensors = if (CollectionLoopCoordinator(context).anySensorCollects()) setOf(LocalUploadModuleFamily.SENSOR) else emptySet()
        val stoppedSensors = sensors.takeIf { !DistributionRestrictedRuntime.hardwareSensorsRunning(context) }.orEmpty()
        val missing = missingAccessFamilies(activeModulePermissionStatus(states, environment)) + stoppedSensors
        // The same check with every grant denied names every family an active module needs.
        val required = missingAccessFamilies(activeModulePermissionStatus(states, environment.copy(
            grantedRuntimePermissions = emptySet(),
            healthConnectGranted = false, usageAccessGranted = false,
            notificationListenerEnabled = false, accessibilityEnabled = false,
        ))) + sensors
        if (missing.isNotEmpty()) Log.i(ACCESS_TAG, "Collection access missing: ${missing.map { it.name }}")
        val lost = recordAccessEpisodes(
            LocalUploadDiagnosticsStore.of(context),
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            "${owner.studyId}:${owner.participantId}",
            missing,
            required,
            notifyInitiallyMissing = stoppedSensors,
        )
        if (lost.isNotEmpty()) notifyAccessMissing(context.applicationContext)
    } catch (e: Exception) {
        Log.w(ACCESS_TAG, "Could not check collection access", e)
    }
}

private fun notifyAccessMissing(appContext: Context) {
    val intent = Intent(appContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(MainActivity.EXTRA_SELECT_TAB, R.id.nav_data_sharing)
    val pending = PendingIntent.getActivity(
        appContext, NOTIFICATION_ID_ACCESS, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val text = appContext.getString(R.string.notif_access_missing)
    val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_notification)
        .setContentTitle(appContext.getString(R.string.notif_action_needed))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setContentIntent(pending)
        .build()
    // Without the permission the Overview card and the server diagnostic still show it.
    postNotificationIfPermitted(appContext, NOTIFICATION_ID_ACCESS, notification)
}
