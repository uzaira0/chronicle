package com.openlattice.chronicle.collection.state

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.CollectionUploadEvidence
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
import com.openlattice.chronicle.collection.usage.UsageCursorMovedException
import com.openlattice.chronicle.collection.usage.UsageEventPoller
import com.openlattice.chronicle.collection.usage.UsageEventsCollectionModule
import com.openlattice.chronicle.collection.usage.UsageModulePersistence
import com.openlattice.chronicle.collection.usage.UsagePollOutcome
import com.openlattice.chronicle.preferences.IDENTIFY_USER
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.upload.UploadWorkerDelegate
import com.openlattice.chronicle.services.upload.decodedUploadBody
import com.openlattice.chronicle.services.usage.collectUsage
import com.openlattice.chronicle.services.usage.buildUsageQueueEntries
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.UserQueueEntry
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.queueKey
import com.openlattice.chronicle.storage.nextQueueWriteTimestamp
import com.openlattice.chronicle.storage.nextDecisionSeq
import java.io.IOException
import java.util.NavigableMap
import java.util.TreeMap
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowUsageStatsManager
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Every queued usage row carries the settings revision its events were captured under. Applying a
 * new revision records the same phone-clock boundary as field projection, so even a delayed poll sends the server
 * the revision that authorizes the optional fields the participant's earlier consent covered.
 */
@Config(sdk = [28], shadows = [UsageRevisionBoundaryTest.CountingUsage::class,
    UsageRevisionBoundaryTest.ApplyNotification::class])
class UsageRevisionBoundaryTest : ResearchErasureTestFixture() {
    @Implements(UsageStatsManager::class)
    class CountingUsage : ShadowUsageStatsManager() {
        companion object {
            @Volatile var reads = 0
            @Volatile var onRead: ((Long, Long) -> Unit)? = null
        }
        @Implementation override fun queryEvents(start: Long, end: Long): UsageEvents {
            reads++
            onRead?.invoke(start, end)
            return super.queryEvents(start, end)
        }
    }

    @Implements(NotificationManager::class)
    class ApplyNotification : ShadowNotificationManager() {
        companion object { @Volatile var onNotify: (() -> Unit)? = null }
        @Implementation override fun notify(tag: String?, id: Int, notification: Notification) {
            super.notify(tag, id, notification)
            onNotify?.invoke()
        }
    }

    private val activity = CollectionModuleId.IN_APP_ACTIVITY_CLASS
    private val user = CollectionModuleId.USER_IDENTIFICATION
    private val on = CollectionModuleSetting(enabled = true)
    private val off = CollectionModuleSetting(enabled = false)

    private fun settings(version: Int, vararg changed: Pair<CollectionModuleId, CollectionModuleSetting>) =
        AndroidDataCollectionSetting(
            modules = AndroidDataCollectionSetting.androidSupportedModuleIds.associateWith { off } +
                mapOf(CollectionModuleId.USAGE_EVENTS to on, activity to on, user to on,
                    CollectionModuleId.UPLOAD_TELEMETRY to on) + changed,
            settingsVersion = version)

    private fun apply(fetched: AndroidDataCollectionSetting) =
        assertEquals(SettingsApplicationOutcome.APPLIED,
            onPersistenceWorker { coordinator.applyFetchedSettings(owner, fence.settingsGeneration(), fetched) })

    private fun usageAccess(mode: Int) =
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid, context.packageName, mode)

    @Before fun enrollAtRevisionOne() {
        usageAccess(AppOpsManager.MODE_ALLOWED)
        prefs.edit().putBoolean(IDENTIFY_USER, true).commit()
        CountingUsage.onRead = null
        ApplyNotification.onNotify = null
        val initial = settings(1)
        val preview = ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(javaClass.getResource("/enrollment-preview.json")!!.readText())!!
        val manifest = preview.manifest.copy(studyId = UUID.fromString(owner.studyId), participantId = owner.participantId,
            serverOrigin = owner.url, collectionSettings = initial, settingsVersion = initial.settingsVersion)
        db.uploadServerDao().update(owner.copy(studyDisclosureJson = ChronicleJson.moshi
            .adapter(MobileEnrollmentManifest::class.java).toJson(manifest),
            disclosureVersion = manifest.participantPolicy.version, manifestDigest = preview.manifestDigest))
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        apply(initial)
        listOf(CollectionModuleId.USAGE_EVENTS, activity, user).forEach {
            assertTrue("$it collects at revision 1", ResearchPersistenceGate.collectsNow(context, it))
        }
        Thread.sleep(5)
        CountingUsage.reads = 0
    }

    private fun foreground(at: Long, activityClass: String) =
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).addEvent(ShadowUsageStatsManager.EventBuilder
            .buildEvent().setPackage("example.app").setClass(activityClass).setTimeStamp(at)
            .setEventType(UsageEvents.Event.ACTIVITY_RESUMED).build())

    /** Uploads the usage queue; each body with its revision header, in upload order. */
    private fun upload(onHeaders: (Map<*, *>) -> Unit = {}): List<Pair<String?, List<ChronicleUsageEvent>>> {
        val sent = mutableListOf<Pair<String?, List<ChronicleUsageEvent>>>()
        var call: Array<out Any?> = emptyArray()
        withStudyApi(onCall = { call = it }) { method ->
            check(method == "uploadAndroidUsageEventData") { "unexpected $method" }
            val events = decodedUploadBody<ChronicleData>(call[3]!!).map { it as ChronicleUsageEvent }
            val headers = call[2] as Map<*, *>
            onHeaders(headers)
            val version = headers[CollectionUploadEvidence.SETTINGS_VERSION_HEADER]?.toString()
            sent += version to events
            UploadReceipt(events.mapTo(linkedSetOf()) { checkNotNull(it.clientEventId) }, emptySet(), checkNotNull(version).toInt())
        }.use { onPersistenceWorker { UploadWorkerDelegate(context, db).execute() } }
        assertEquals(0, db.queueEntryData().getSize())
        return sent
    }

    /** (revision header, activity class, user label) for each uploaded event, in capture order. */
    private fun uploadedEvents() = upload().flatMap { (version, events) -> events.map { version to it } }
        .sortedBy { it.second.timestamp }.map { (version, event) -> Triple(version, event.activityClass, event.user) }

    /** An unread event captured at revision 1, a revision 2 that disables [disabled], then one event after it. */
    private fun captureAcrossRevisionThatDisables(disabled: CollectionModuleId) {
        val labelledAt = System.currentTimeMillis()
        db.userQueueEntryData().insertEntries(listOf(UserQueueEntry(labelledAt, "labelled-user")))
        foreground(labelledAt + 1, "BeforeActivity")
        Thread.sleep(5)
        apply(settings(2, disabled to off))
        Thread.sleep(5)
        foreground(System.currentTimeMillis(), "AfterActivity")
        Thread.sleep(5)
        assertTrue(onPersistenceWorker { collectUsage(context) })
    }

    @Test fun anActivityClassDisableKeepsTheFieldOnUsageCapturedUnderTheEarlierRevision() {
        captureAcrossRevisionThatDisables(activity)
        assertEquals(listOf(Triple("1", "BeforeActivity", "labelled-user"), Triple("2", null, "labelled-user")),
            uploadedEvents())
    }

    @Test fun aUserIdentificationDisableKeepsTheLabelOnUsageCapturedUnderTheEarlierRevision() {
        captureAcrossRevisionThatDisables(user)
        assertEquals(listOf(Triple("1", "BeforeActivity", "labelled-user"), Triple("2", "AfterActivity", "")),
            uploadedEvents())
    }

    @Test fun anApplyWhileHaltedReadsNoUsage() {
        foreground(System.currentTimeMillis(), "HaltedActivity")
        // The participant declined a module the study requires: the hard stop holds every collector.
        stopOnPersistenceWorker {
            db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(activity.id, true,
                ParticipantDecision.DECLINED.name, 0, true, 1)))
        }
        assertTrue(CollectionHardStop.applies(context))
        apply(settings(2, activity to CollectionModuleSetting(enabled = true, required = true)))
        assertEquals(0, CountingUsage.reads)
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test fun anApplyWithNoUnreadUsageQueuesNothing() {
        apply(settings(2, activity to off))
        assertEquals(1, CountingUsage.reads)
        assertEquals(0, db.queueEntryData().getSize())
        assertEquals(emptyList<Any>(), uploadedEvents())
    }

    @Test fun reapplyingTheSameRevisionReadsUsageOnce() {
        foreground(System.currentTimeMillis(), "BeforeActivity")
        Thread.sleep(5)
        apply(settings(2, activity to off))
        apply(settings(2, activity to off))
        assertEquals(1, CountingUsage.reads)
        assertEquals(1, db.queueEntryData().getSize())
    }

    /** A poll already reading when the revision applies leaves its window to the apply's read: no event queues twice. */
    @Test fun aPollRacingTheApplyQueuesEachEventOnce() {
        val labelledAt = System.currentTimeMillis()
        db.userQueueEntryData().insertEntries(listOf(UserQueueEntry(labelledAt, "labelled-user")))
        foreground(labelledAt + 1, "BeforeActivity")
        Thread.sleep(5)
        var raced = false
        var tailInserted = false
        CountingUsage.onRead = { _, end ->
            if (!raced) {
                raced = true
                apply(settings(2, activity to off))
            } else if (!tailInserted) {
                tailInserted = true
                foreground(end, "TailActivity")
                Thread.sleep(5)
            }
        }
        try {
            assertTrue(onPersistenceWorker { collectUsage(context) })
        } finally { CountingUsage.onRead = null }
        assertTrue(raced)
        assertTrue(tailInserted)
        assertEquals(listOf(Triple("1", "BeforeActivity", "labelled-user"),
            Triple("1", "TailActivity", "labelled-user")), uploadedEvents())
    }

    @Test fun unreadFieldsKeepTheirRevisionWhenUsageAccessIsOffDuringApply() {
        val labelledAt = System.currentTimeMillis()
        db.userQueueEntryData().insertEntries(listOf(UserQueueEntry(labelledAt, "labelled-user")))
        foreground(labelledAt + 1, "BeforeActivity")
        Thread.sleep(5)
        usageAccess(AppOpsManager.MODE_IGNORED)
        apply(settings(2, activity to off, user to off))
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        Thread.sleep(5)
        foreground(System.currentTimeMillis(), "AfterActivity")
        Thread.sleep(5)
        usageAccess(AppOpsManager.MODE_ALLOWED)
        assertTrue(onPersistenceWorker { collectUsage(context) })
        assertEquals(listOf(Triple("1", "BeforeActivity", "labelled-user"), Triple("2", null, "")),
            uploadedEvents())
    }

    @Test fun aProviderFailureBeforeApplyDoesNotLoseTheUnreadRevisionOnRetry() {
        foreground(System.currentTimeMillis(), "BeforeActivity")
        Thread.sleep(5)
        CountingUsage.onRead = { _, _ -> throw IOException("usage provider unavailable") }
        try {
            assertThrows(IOException::class.java) { onPersistenceWorker { collectUsage(context) } }
            apply(settings(2, activity to off))
        } finally { CountingUsage.onRead = null }
        assertTrue(onPersistenceWorker { collectUsage(context) })
        assertEquals(listOf(Triple("1", "BeforeActivity", "")), uploadedEvents())
    }

    @Test fun oneDeferredPollSplitsEventsAcrossEveryAppliedRevision() {
        usageAccess(AppOpsManager.MODE_IGNORED)
        val labelledAt = System.currentTimeMillis()
        db.userQueueEntryData().insertEntries(listOf(UserQueueEntry(labelledAt, "labelled-user")))
        foreground(labelledAt + 1, "BeforeActivity")
        Thread.sleep(5)
        apply(settings(2, activity to off))
        Thread.sleep(5)
        foreground(System.currentTimeMillis(), "MiddleActivity")
        Thread.sleep(5)
        apply(settings(3, activity to off, user to off))
        Thread.sleep(5)
        foreground(System.currentTimeMillis(), "AfterActivity")
        Thread.sleep(5)
        usageAccess(AppOpsManager.MODE_ALLOWED)
        assertTrue(onPersistenceWorker { collectUsage(context) })
        val rows = db.queueEntryData().getNextEntries(10)
        assertEquals(listOf(1, 2, 3), rows.map { row ->
            db.uploadQueueEvidenceDao().get(UploadQueueTable.USAGE.name, listOf(row.queueKey)).single().settingsVersion
        })
        assertEquals(listOf(Triple("1", "BeforeActivity", "labelled-user"),
            Triple("2", null, "labelled-user"), Triple("3", null, "")), uploadedEvents())
    }

    /** A deferred batch over the queue's payload bound splits on insert: every split row keeps its capture revision. */
    @Test fun anOversizedDeferredBatchKeepsItsRevisionOnEverySplitRow() {
        usageAccess(AppOpsManager.MODE_IGNORED)
        val start = System.currentTimeMillis()
        val longClass = "Activity" + "x".repeat(1_500)
        repeat(800) { foreground(start + it, "$longClass$it") }
        Thread.sleep(805)
        apply(settings(2, user to off))
        Thread.sleep(5)
        usageAccess(AppOpsManager.MODE_ALLOWED)
        assertTrue(onPersistenceWorker { collectUsage(context) })
        val rows = db.queueEntryData().getNextEntries(100)
        assertTrue("the batch split on insert", rows.size > 1)
        assertEquals(rows.map { 1 }, rows.map { row ->
            db.uploadQueueEvidenceDao().get(UploadQueueTable.USAGE.name, listOf(row.queueKey)).single().settingsVersion
        })
    }

    @Test fun anEventAtTheExclusivePollEndKeepsTheRevisionInForceWhenCaptured() {
        foreground(System.currentTimeMillis(), "BeforeActivity")
        Thread.sleep(5)
        var inserted = false
        CountingUsage.onRead = { _, end ->
            if (!inserted) {
                inserted = true
                foreground(end, "AtPollEndActivity")
                Thread.sleep(5)
            }
        }
        try { apply(settings(2, activity to off)) } finally { CountingUsage.onRead = null }
        assertTrue(inserted)
        Thread.sleep(5)
        assertTrue(onPersistenceWorker { collectUsage(context) })
        val uploaded = uploadedEvents()
        assertEquals(Triple("1", "BeforeActivity", ""), uploaded.first())
        assertEquals(Triple("1", "AtPollEndActivity", ""), uploaded.last())
    }

    @Test fun anEventAfterTheRevisionBoundaryDuringApplyHasNoDisabledField() {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        var inserted = false
        ApplyNotification.onNotify = {
            if (!inserted) {
                assertEquals(2, db.collectionModuleStateDao().get(activity.id)!!.appliedVersion)
                assertEquals(1, ResearchPersistenceGate.currentSettingsVersion())
                inserted = true
                Thread.sleep(5)
                foreground(System.currentTimeMillis(), "DuringApplyActivity")
                Thread.sleep(5)
            }
        }
        try { apply(settings(2, activity to off)) } finally { ApplyNotification.onNotify = null }
        assertTrue("Inserted after the state commit, before authorization publication", inserted)
        assertTrue(onPersistenceWorker { collectUsage(context) })
        assertEquals(listOf(Triple("2", null, "")), uploadedEvents())
    }

    @Test fun reconsentBeforeAccessReturnsDoesNotRewriteTheOldRevisionsDecisionEvidence() {
        stopOnPersistenceWorker {
            fence.accepted(setOf(CollectionModuleId.USAGE_EVENTS, activity, user), initialReview = true)
            assertEquals(1L, db.nextDecisionSeq())
        }
        foreground(System.currentTimeMillis(), "BeforeActivity")
        Thread.sleep(5)
        usageAccess(AppOpsManager.MODE_IGNORED)
        apply(settings(2, activity to off))
        apply(settings(3))
        withStudyApi { method -> check(method == "reportCollectionAck"); OK() }.use {
            assertTrue(onPersistenceWorker {
                coordinator.applyDecisions(setOf(activity), emptySet(), ConsentTrigger.SETTINGS_CHANGE)
            })
        }
        Thread.sleep(5)
        foreground(System.currentTimeMillis(), "AfterActivity")
        Thread.sleep(5)
        usageAccess(AppOpsManager.MODE_ALLOWED)
        assertTrue(onPersistenceWorker { collectUsage(context) })
        val evidence = mutableMapOf<String?, String?>()
        val sent = upload { headers ->
            evidence[headers[CollectionUploadEvidence.SETTINGS_VERSION_HEADER]?.toString()] =
                headers[CollectionUploadEvidence.DECISION_SEQ_HEADER]?.toString()
        }
        assertEquals(mapOf("1" to "1", "3" to "2"), evidence)
        assertEquals(listOf("BeforeActivity", "AfterActivity"), sent.flatMap { it.second }
            .sortedBy { it.timestamp }.map { it.activityClass })
    }

    @Test fun aLongerLosingPollRollsBackAndLeavesItsTailForRetry() {
        val start = System.currentTimeMillis()
        val events = listOf(event(start + 100), event(start + 700))
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.USAGE_EVENTS)!!
        val checkpoints = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), scope.key, scope.floorMillis)
        checkpoints.readOrRememberStart { start }
        val module = UsageEventsCollectionModule(object : UsageEventPoller {
            override fun poll(previousPollTimestamp: Long, currentPollTimestamp: Long,
                              users: NavigableMap<Long, String>) = ChronicleData(events.filter {
                it.timestamp.toInstant().toEpochMilli() in previousPollTimestamp until currentPollTimestamp
            })
        }, checkpoints, { start })
        fun poll(end: Long) = module.pollWindow(TreeMap(), CollectionWindow(0, end))!!
        fun persist(outcome: UsagePollOutcome) = UsageModulePersistence.persist(
            buildUsageQueueEntries(outcome.events, db.nextQueueWriteTimestamp()) { System.nanoTime() },
            outcome.currentPollTimestamp, ResearchPersistenceGate.usageSink(context),
            { module.commitCheckpoint(outcome) }, { db.runInTransaction(it) })
        val short = poll(start + 500)
        val long = poll(start + 900)
        assertTrue(persist(short))
        assertThrows(UsageCursorMovedException::class.java) { persist(long) }
        assertEquals(start + 500, checkpoints.readPreviousPollTimestamp())
        assertEquals(1, db.queueEntryData().getSize())
        assertTrue(persist(poll(start + 900)))
        assertEquals(listOf(Triple("1", "MainActivity", "participant-A"),
            Triple("1", "MainActivity", "participant-A")), uploadedEvents())
    }
}
