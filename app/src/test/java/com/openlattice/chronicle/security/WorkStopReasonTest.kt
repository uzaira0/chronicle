package com.openlattice.chronicle.security

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import com.openlattice.chronicle.audit.auditWorkerParameters
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WorkStopReasonTest {
    @Test @Config(sdk = [30, Build.VERSION_CODES.S, 36])
    fun stoppedWorkLogsQuotaReasonWhenThePlatformSupportsIt() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val worker = object : LeaseBoundWorker(app, auditWorkerParameters()) {
            override fun runWork(): Result = Result.success()
        }
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) WorkInfo.STOP_REASON_QUOTA else WorkInfo.STOP_REASON_UNKNOWN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) worker.stop(reason) else worker.onStopped()
        assertTrue(ShadowLog.getLogsForTag("LeaseBoundWorker").any { it.msg.contains("reason=$reason") })
    }
}
