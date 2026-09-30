package com.openlattice.chronicle.collection.state

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Synchronous persistence tests exercise the background-caller contract, including setup. */
class BackgroundPersistenceRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            onPersistenceWorker { base.evaluate() }
        }
    }
}

internal fun <T> onPersistenceWorker(action: () -> T): T {
    val task = FutureTask { action() }
    Thread(task, "persistence-test").apply { isDaemon = true }.start()
    try { return task.get(60, TimeUnit.SECONDS) }
    catch (error: ExecutionException) { throw error.cause ?: error }
    finally { task.cancel(true) }
}

internal fun stopOnPersistenceWorker(action: () -> Unit) = onPersistenceWorker { ResearchPersistenceGate.stop(action) }

internal fun stopOnPersistenceWorker(operation: ResearchPersistenceGate.PrivacyOperation, action: () -> Unit) =
    onPersistenceWorker { ResearchPersistenceGate.stop(operation, stopAction = action) }
