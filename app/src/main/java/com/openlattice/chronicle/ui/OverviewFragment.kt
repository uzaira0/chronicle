package com.openlattice.chronicle.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.padForSystemBars
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OverviewFragment : Fragment(R.layout.fragment_overview) {
    private var refreshJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.overviewContent).padForSystemBars()
    }

    override fun onResume() {
        super.onResume()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val snapshot = DashboardDataRepository.load(requireContext())
                view?.let { bind(it, snapshot) }
                delay(DASHBOARD_REFRESH_MS)
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        super.onPause()
    }

    private fun bind(view: View, snapshot: DashboardSnapshot) {
        view.findViewById<TextView>(R.id.overviewStudyId).text =
            getString(R.string.overview_study, snapshot.studyId)
        view.findViewById<TextView>(R.id.overviewParticipantId).text =
            getString(R.string.overview_participant, snapshot.participantId)
        view.findViewById<TextView>(R.id.overviewLastUpload).text =
            snapshot.lastUpload
        view.findViewById<TextView>(R.id.overviewLatestTimestamp).text =
            getString(R.string.overview_latest_timestamp, snapshot.latestTimestampUploaded)
        val collectionStatus = view.findViewById<TextView>(R.id.overviewCollectionStatus)
        if (snapshot.collection.waitingReview > 0) {
            // A module is awaiting a decision. Make the card a persistent shortcut into the Data
            // Sharing tab, where the participant reviews and turns each pending module on or off.
            collectionStatus.text =
                getString(R.string.overview_collection_status_review, snapshot.collection.message)
            collectionStatus.isClickable = true
            collectionStatus.isFocusable = true
            collectionStatus.setOnClickListener {
                (activity as? MainActivity)?.selectTab(R.id.nav_data_sharing)
            }
        } else {
            collectionStatus.text = getString(R.string.overview_collection_status, snapshot.collection.message)
            collectionStatus.isClickable = false
            collectionStatus.setOnClickListener(null)
        }
        view.findViewById<TextView>(R.id.overviewServerHealth).text =
            getString(R.string.overview_server_health, snapshot.serverHealth.message)
    }
}
