package com.openlattice.chronicle.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.capability.CollectionCapabilityResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverviewFragment : Fragment(R.layout.fragment_overview) {
    private var refreshJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    }

    override fun onResume() {
        super.onResume()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch(storageFailureHandler()) {
            // Grants change only in system Settings, which resumes this fragment on return.
            val environment = withContext(Dispatchers.IO) {
                CollectionCapabilityResolver.snapshot(requireContext().applicationContext)
            }
            while (true) {
                val snapshot = DashboardDataRepository.load(requireContext())
                val accessNeeded = activeModulePermissionStatus(snapshot.collectionModules, environment).hasMissing
                view?.let { bind(it, snapshot, accessNeeded) }
                delay(DASHBOARD_REFRESH_MS)
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        super.onPause()
    }

    private fun bind(view: View, snapshot: DashboardSnapshot, accessNeeded: Boolean) {
        view.findViewById<TextView>(R.id.overviewStudyId).text =
            getString(R.string.overview_study, snapshot.studyId)
        view.findViewById<TextView>(R.id.overviewParticipantId).text =
            getString(R.string.overview_participant, snapshot.participantId)
        view.findViewById<TextView>(R.id.overviewLastUpload).text =
            snapshot.lastUpload
        view.findViewById<TextView>(R.id.overviewLatestTimestamp).text =
            getString(R.string.overview_latest_timestamp, snapshot.latestTimestampUploaded)
        val collectionStatus = view.findViewById<TextView>(R.id.overviewCollectionStatus)
        if (snapshot.collection.waitingReview > 0 || accessNeeded) {
            // A module is awaiting a decision, or an accepted module still lacks its OS access
            // (so it counts as active but collects nothing). Make the card a persistent shortcut
            // into the Data Sharing tab, where the participant reviews each module and its access.
            collectionStatus.text = getString(
                if (snapshot.collection.waitingReview > 0) {
                    R.string.overview_collection_status_review
                } else {
                    R.string.overview_collection_status_access
                },
                snapshot.collection.message,
            )
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
