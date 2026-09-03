package com.openlattice.chronicle.services.sinks

import android.util.Log
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.crypto.EncryptionRequiredButUnavailableException
import com.openlattice.chronicle.services.crypto.PayloadSealer
import com.openlattice.chronicle.study.StudyEncryptionSetting
import com.openlattice.chronicle.telemetry.LocalTelemetry
import java.util.*

class ChronicleUploadSink(
    private val studyId: UUID,
    private val participantId: String,
    private val deviceId: String,
    private val apiKey: String?,
    private val studyApi: ChronicleStudyApi,
    /**
     * The study's cached payload-encryption setting (HIPAA-2028 W2), or null when none is
     * cached. When [PayloadSealer.isEncryptionEnabled] is true the batch is sealed and posted to
     * the encrypted endpoint; otherwise the existing plaintext upload is used, unchanged.
     */
    private val encryptionSetting: StudyEncryptionSetting? = null,
    /**
     * Whether this study is known to require e2ee ([EncryptionSettingStore.isEncryptionRequired]).
     * When true but [encryptionSetting] carries no usable key, the sink fails closed (throws) rather
     * than uploading PHI in plaintext, so the batch is retained and retried.
     */
    private val encryptionRequired: Boolean = false,
) : DataSink {
    override fun submit(data: List<ChronicleSample>): Map<String, Boolean> {
        val written = try {
            when (PayloadSealer.routing(encryptionSetting, encryptionRequired)) {
                PayloadSealer.EncryptionRouting.ENCRYPT -> {
                    // Seal the EXACT bytes the plaintext path would post: ChronicleData(data)
                    // Serialize with the same boundary used by the plaintext Retrofit path.
                    val plaintext = JsonSerializer.serializeToBytes(ChronicleData(data))
                    val envelope = PayloadSealer.seal(
                        setting = encryptionSetting!!,
                        studyId = studyId,
                        participantId = participantId,
                        payloadType = EncryptedPayloadType.USAGE,
                        plaintext = plaintext,
                        sampleCount = data.size,
                    )
                    studyApi.uploadAndroidEncryptedData(
                        studyId,
                        participantId,
                        deviceId,
                        apiKey,
                        listOf(envelope),
                    )
                }
                PayloadSealer.EncryptionRouting.FAIL_CLOSED ->
                    // e2ee required but no usable key cached: do NOT upload plaintext PHI; the
                    // throw propagates to UploadExecutor, leaving the queue cursor unadvanced so
                    // the batch is retained and retried after the next successful settings sync.
                    throw EncryptionRequiredButUnavailableException(studyId)
                PayloadSealer.EncryptionRouting.PLAINTEXT ->
                    studyApi.uploadAndroidUsageEventData(
                        studyId,
                        participantId,
                        deviceId,
                        apiKey,
                        ChronicleData(data)
                    )
            }
        } catch (e: Exception) {
            LocalTelemetry.recordException(e)
            LocalTelemetry.logEvent(TelemetryEvents.SUBMIT_FAILURE, null)
            Log.i(javaClass.name, "Exception when uploading data", e)
            throw e
        }
        return mapOf(
            ChronicleUploadSink::class.java.name to (written > 0)
        )
    }
}
