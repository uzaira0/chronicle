package com.openlattice.chronicle.collection.state

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.work.WorkManager
import com.openlattice.chronicle.EnrolledWork
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.SecurePreferencesUnavailableException
import com.openlattice.chronicle.services.notifications.REMINDER_SCHEDULE_LOCK
import com.openlattice.chronicle.services.notifications.cancelReminderByRequestCode
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadQueueTable

/**
 * Withdrawal, a standing decline of a required module and the dashboard's Mark not enrolled are one hard stop:
 * nothing queued uploads and nothing collects. Data already on the server stays.
 *
 * Each trigger first writes its own durable mark: the withdrawal state, the NOT_ENROLLED status, the declined
 * required module. [applies] reads those marks, and the upload gate refuses on them alone. [enforce] runs at the end
 * of every research stop, under its write side, so the queues are cleared before any upload is admitted again, and a
 * process death between the mark and the clear is finished by the next start, whose initialization is a stop.
 * The enrollment monitor and the settings sync keep running: they carry a re-enrollment, a re-acceptance and the
 * decline's acknowledgment to the server.
 */
internal object CollectionHardStop {
    /**
     * Every upload stream and the diagnostics queue, plus the queues [UploadQueueTable] does not list: user labels,
     * sensor dead letters, row evidence. The local quarantine never uploads and stays for support.
     */
    internal val QUEUES = UploadQueueTable.entries.filter { it != UploadQueueTable.QUARANTINE }.map { it.tableName } +
        listOf("userQueue", "sensor_sample_dead_letters", "upload_queue_evidence")

    /**
     * The [QUEUES] a capture window cannot judge: pending diagnostics and sensor dead letters.
     * Identity labels carry writeTimestamp; row evidence leaves with its row (the queues' delete triggers).
     */
    internal val UNTIMED_QUEUES = QUEUES - UploadQueueTable.entries.filter { it.uploaded }.mapTo(hashSetOf()) { it.tableName } -
        setOf("upload_queue_evidence", "userQueue")

    @WorkerThread
    fun applies(context: Context): Boolean =
        WithdrawalStateStore(context).stateOrThrow() != WithdrawalState.NONE ||
            EnrollmentSettings(context).getParticipationStatus() == ParticipationStatus.NOT_ENROLLED ||
            CollectionLoopStore.of(context).haltedByDeclinedRequired()

    /**
     * Caller holds the research stop. While the marks cannot be read (secure storage locked before the first unlock)
     * the upload gate fails closed on the same read, so the clear waits for the next stop instead of failing this one.
     */
    fun enforce(context: Context) {
        val stopped = try { applies(context) } catch (_: SecurePreferencesUnavailableException) { return }
        if (!stopped) return
        clearQueues(ChronicleDb.getInstance(context))
        ParticipantWithdrawalManager.enforceCollectionStopped(context)
        synchronized(REMINDER_SCHEDULE_LOCK) {
            EnrollmentSettings(context).getMobileReminderRequestCodes().forEach { cancelReminderByRequestCode(context, it) }
        }
        val work = WorkManager.getInstance(context)
        EnrolledWork.collectionWorkNames().forEach(work::cancelUniqueWork)
    }

    /**
     * Reschedules the work [enforce] cancelled once the stop that applied ([wasStopped]) no longer does.
     */
    fun resumeWorkIfLifted(context: Context, wasStopped: Boolean) {
        if (wasStopped && !applies(context)) EnrolledWork.reconcile(context)
    }

    internal fun clearQueues(db: ChronicleDb, queues: List<String> = QUEUES) = db.runInTransaction {
        val sql = db.openHelper.writableDatabase
        queues.forEach { sql.execSQL("DELETE FROM `$it`") }
    }
}
