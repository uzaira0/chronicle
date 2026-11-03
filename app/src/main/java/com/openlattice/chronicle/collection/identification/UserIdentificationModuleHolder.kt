package com.openlattice.chronicle.collection.identification

import com.openlattice.chronicle.preferences.CURRENT_USER
import com.openlattice.chronicle.preferences.IDENTIFY_USER
import android.content.Context
import android.content.SharedPreferences
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.preferences.configuredStudyModuleEnabled
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UserQueueEntry
import com.openlattice.chronicle.storage.currentUserOr
import com.openlattice.chronicle.storage.UserStorageQueue
import java.util.UUID
import com.openlattice.chronicle.collection.state.CollectionLoopStore

/** Builds identification against the current stores, including after local database recovery. */
public object UserIdentificationModuleHolder {
    public fun get(context: Context): UserIdentificationCollectionModule = build(context.applicationContext)

    private fun build(appContext: Context): UserIdentificationCollectionModule {
        val db = ChronicleDb.getInstance(appContext)
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(appContext)
        val uploadServerDao = db.uploadServerDao()
        val collectionLoopStore = CollectionLoopStore(db.collectionModuleStateDao())

        return UserIdentificationCollectionModule(
            store = PrefsAndRoomTargetUserStore(
                userQueueDao = db.userQueueEntryData(),
                prefs = prefs,
                currentUserKey = CURRENT_USER,
            ),
            // Final write-path check: the local choice may narrow the authenticated study scope but
            // can never authorize USER_IDENTIFICATION on its own. Keep only Context-free handles
            // in this app-scoped holder.
            userIdentificationEnabled = {
                val rawStudyId = prefs.getString(STUDY_ID, null)
                val studyId = rawStudyId?.let { value ->
                    runCatching { UUID.fromString(value) }
                        .getOrNull()
                        ?.takeIf { it.toString() == value }
                }
                val participantId = prefs.getString(PARTICIPANT_ID, null).orEmpty()
                prefs.getBoolean(IDENTIFY_USER, false) &&
                    studyId != null &&
                    participantId.isNotBlank() &&
                    runCatching {
                        configuredStudyModuleEnabled(
                            uploadServerDao.getConfiguredServer(),
                            studyId,
                            participantId,
                            CollectionModuleId.USER_IDENTIFICATION,
                            collectionLoopStore.loadAll()[CollectionModuleId.USER_IDENTIFICATION],
                        )
                    }.getOrDefault(false)
            },
        )
    }
}

/** Room history is authoritative; preferences mirror committed participant labels. */
public class PrefsAndRoomTargetUserStore(
    private val userQueueDao: UserStorageQueue,
    private val prefs: SharedPreferences,
    private val currentUserKey: String,
) : TargetUserStore {

    override fun insertUserQueueEntry(entry: UserQueueEntry) {
        userQueueDao.insertEntries(listOf(entry))
    }

    override fun writeCurrentUserPref(user: String) {
        check(prefs.edit().putString(currentUserKey, user).commit()) {
            "Failed to persist current-user preference"
        }
    }

    override fun readCurrentUserPref(unassignedDefault: String): String =
        userQueueDao.currentUserOr(unassignedDefault)

    override fun userQueueDepth(): Int = userQueueDao.getUserTimestamps().size
}
