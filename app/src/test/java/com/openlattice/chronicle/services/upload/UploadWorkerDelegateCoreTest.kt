package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadWorkerDelegateCoreTest {

    private fun server(id: Long) = UploadServerEntity(
        id = id,
        name = "server-$id",
        url = "https://chronicle-screentime-app.research.bcm.edu",
        studyId = "00000000-0000-0000-0000-000000000001",
        participantId = "participant-$id",
        sourceDeviceId = "device-$id",
    )

    @Test
    fun attemptsEveryEligibleServerAfterAPartialFailure() {
        val attempted = mutableListOf<Long>()
        val failed = mutableListOf<Long>()
        var cleanedUp = false

        val failureCount = runUsageUploadForEligibleServers(
            servers = listOf(server(1), server(2), server(3)),
            uploadForServer = { server ->
                attempted.add(server.id)
                if (server.id == 2L) throw IllegalStateException("server 2 failed")
            },
            recordFailure = { server, _ -> failed.add(server.id) },
            afterUploads = { cleanedUp = true },
        )

        assertEquals(listOf(1L, 2L, 3L), attempted)
        assertEquals(listOf(2L), failed)
        assertEquals(1, failureCount)
        assertTrue(cleanedUp)
    }

    @Test
    fun countsEveryFailedServerAndStillRunsCleanup() {
        val failed = mutableListOf<Long>()
        var cleanedUp = false

        val failureCount = runUsageUploadForEligibleServers(
            servers = listOf(server(1), server(2), server(3)),
            uploadForServer = { throw IllegalStateException("failed") },
            recordFailure = { server, _ -> failed.add(server.id) },
            afterUploads = { cleanedUp = true },
        )

        assertEquals(listOf(1L, 2L, 3L), failed)
        assertEquals(3, failureCount)
        assertTrue(cleanedUp)
    }

}
