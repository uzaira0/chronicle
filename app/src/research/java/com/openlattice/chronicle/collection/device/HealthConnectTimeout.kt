package com.openlattice.chronicle.collection.device

import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val HEALTH_CONNECT_TIMEOUT_MS = 10_000L
private val healthConnectExecutor = Executors.newCachedThreadPool { task ->
    Thread(task, "chronicle-health-connect").apply { isDaemon = true }
}

/** Bounds even SDK suspensions that do not respond to coroutine cancellation. */
internal fun <T> awaitHealthConnect(action: suspend () -> T): T {
    val request = healthConnectExecutor.submit<T> { runBlocking { action() } }
    try {
        return request.get(HEALTH_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (error: TimeoutException) {
        request.cancel(true)
        throw error
    } catch (error: InterruptedException) {
        request.cancel(true)
        Thread.currentThread().interrupt()
        throw error
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }
}

internal fun grantedHealthConnectPermissions(client: HealthConnectClient): Set<String> =
    awaitHealthConnect { client.permissionController.getGrantedPermissions() }
