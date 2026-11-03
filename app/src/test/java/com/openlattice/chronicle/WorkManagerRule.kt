package com.openlattice.chronicle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.impl.WorkManagerImpl
import java.util.concurrent.Executor
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Gives each test its own WorkManager and a clean [WorkSchedulingStatus]. Robolectric shares one
 * classloader per SDK across test classes, so WorkManager's process singleton (with its executors,
 * schedulers and Processor) and a pending scheduling retry otherwise leak into the next test.
 *
 * This installs a real [WorkManagerImpl] from the library's own factory rather than work-testing's
 * TestWorkManagerImpl, which swaps SystemJobScheduler and GreedyScheduler for a single TestScheduler:
 * tests here assert on the OS scheduler's bookkeeping and on ForceStopRunnable reconciliation.
 */
class WorkManagerRule : TestRule {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var installed: WorkManagerImpl? = null

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            WorkSchedulingStatus.reset()
            install()
            try { base.evaluate() } finally {
                WorkSchedulingStatus.reset()
                installed?.closeDatabase()
                installed = null
                WorkManagerImpl.setDelegate(null)
            }
        }
    }

    /** Replaces this test's WorkManager. By default workers never run: tests drive them directly. */
    fun install(
        configuration: Configuration = Configuration.Builder().setExecutor(Executor { }).build(),
        create: (Context, Configuration) -> WorkManagerImpl = { app, config -> WorkManagerImpl(app, config) },
    ): WorkManagerImpl {
        installed?.closeDatabase()
        // The library's own factory (JVM name createWorkManager), the one WorkManager.initialize uses.
        return create(context, configuration).also {
            installed = it
            WorkManagerImpl.setDelegate(it)
        }
    }
}
