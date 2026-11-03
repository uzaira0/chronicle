package com.openlattice.chronicle.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.constants.PARTICIPANT_ID
import com.openlattice.chronicle.constants.STUDY_ID
import com.openlattice.chronicle.preferences.EnrollmentSettings
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EnrollmentStorageBehaviorTest {
    @Test fun identityUsesTheSecureProviderAndRoutingRejectsAnotherStudyOrParticipant() = onPersistenceWorker {
        val base: Context = ApplicationProvider.getApplicationContext()
        val ordinary = mutableListOf<SharedPreferences>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(name, mode).also { ordinary += it }
        }
        val db = EnrollmentTestStores.install(base, enrolled = true)
        try {
            val secure = checkNotNull(EnrollmentTestStores.prefs)
            ordinary.clear()
            val original = db.uploadServerDao().getConfiguredServer()!!
            val server = original.copy(studyId = UUID.randomUUID().toString(), participantId = "new-participant")
            db.uploadServerDao().update(server)
            secure.edit().clear().commit()
            val settings = EnrollmentSettings(context)
            settings.setStudyId(UUID.fromString(server.studyId))
            settings.setParticipantId(server.participantId)
            assertEquals(server.studyId, secure.getString(STUDY_ID, null))
            assertEquals(server.participantId, secure.getString(PARTICIPANT_ID, null))
            assertTrue(ordinary.all { !it.contains(STUDY_ID) && !it.contains(PARTICIPANT_ID) })
            assertTrue(settings.hasConfiguredEnrollmentOrThrow())
            for (other in listOf(server.copy(studyId = UUID.randomUUID().toString()), server.copy(participantId = "another"))) {
                db.uploadServerDao().update(other)
                assertFalse(EnrollmentSettings(context).hasConfiguredEnrollmentOrThrow())
                secure.edit().putString(STUDY_ID, server.studyId).putString(PARTICIPANT_ID, server.participantId).commit()
            }
        } finally { db.close(); EnrollmentTestStores.db = null; EnrollmentTestStores.prefs = null }
    }
}
