package com.openlattice.chronicle.ui

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.audit.AuditStores
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** After a completed withdrawal (seen on a Pixel), Overview showed a zero study id "ended by the study team". */
@RunWith(RobolectricTestRunner::class)
class NotEnrolledOverviewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun deviceWithNoEnrollmentSaysNotEnrolledInsteadOfAStudyEndedByTheTeam() {
        AuditStores.install(context, enrolled = false)
        val snapshot = runBlocking { DashboardDataRepository.load(context) }
        assertEquals(ParticipationStop.NOT_ENROLLED, snapshot.participationStop)
        assertNull(snapshot.studyId)
        assertEquals("Off — not enrolled in a study", snapshot.collection.message)
        val fragment = OverviewFragment()
        withHostedFragment(fragment) {
            val study = fragment.requireView().findViewById<TextView>(R.id.overviewStudyId)
            awaitDashboardUi { study.text.toString() == context.getString(R.string.overview_study, "None") }
        }

        assertEquals(
            "Participant\nNot enrolled in a study. Nothing is collected or uploaded.",
            context.getString(ParticipationStop.NOT_ENROLLED.copy.participant, snapshot.participantId),
        )
    }
}
