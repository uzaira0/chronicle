package com.openlattice.chronicle.release

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.StudyDisclosureCopy
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.study.WithdrawalMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WithdrawalCopyContractTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val policy get() = context.getString(R.string.platform_privacy_policy_full)

    @Test fun everyFlavorsPolicyNamesTheInAppWithdrawalControl() {
        assertTrue(policy.contains("Withdraw from study in Chronicle Settings"))
        assertFalse(policy.contains("no in-app withdrawal"))
    }

    @Test fun eraseStudyConsentDescribesTheWithdrawalPolicyWithoutPromisingErasure() {
        val labels = StudyDisclosureCopy.Labels.fromResources(context)
        val preview = EnrollmentTestStores.enrollmentPreview()
        val body = StudyDisclosureCopy.from(
            preview.copy(manifest = preview.manifest.copy(withdrawalMode = WithdrawalMode.ERASE)), labels,
        ).body
        assertTrue(body.contains("Withdrawing stops collection at once. Data already collected is kept or erased by " +
            "the study team, according to this study's withdrawal policy."))
        assertFalse(body.contains("Withdrawing erases"))
    }

    @Test fun policiesDiscloseDeviceFieldsAndRedactedDiagnosticsWithoutStoredIpClaims() {
        listOf("device model", "manufacturer", "Android version", "diagnostic", "HTTP status", "exception class").forEach {
            assertTrue("Policy must disclose $it", policy.contains(it))
        }
        assertFalse(policy.contains("IP address"))
        assertTrue(policy.contains("October 7, 2026"))
        assertTrue(policy.contains("parent or guardian permission"))
    }

    /** Each diagnostic occurrence uploads on its own (UploadDiagnosticsUploader); only the phone's history is per day. */
    @Test fun policiesDescribeIndividualDiagnosticOccurrencesNotDailyAggregates() {
        val spanish = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
        }).getString(R.string.platform_privacy_policy_full)
        assertTrue(policy.contains("Chronicle reports upload success, failure, retry, collection-access loss, app crash, " +
            "native crash and app not responding diagnostics as separate records, each with its count and occurrence time"))
        assertTrue(spanish.contains("Chronicle informa por separado cada registro de envíos exitosos, fallidos y reintentados"))
        listOf(policy, spanish).forEach {
            assertFalse(it.contains("daily aggregates") || it.contains("agregados diarios"))
            assertFalse(it.contains("first and last occurrence") || it.contains("la primera y la última vez"))
        }
    }

    @Test fun openFlavorPolicyDescribesTheSlimBuild() {
        assumeTrue(DistributionChannel.current() == DistributionChannel.OPEN)
        listOf("random UUID per enrollment", "list of hardware sensors", "hardware sensor measurements",
            "does not collect Interaction Events module records (taps and gestures inside apps), " +
                "Notification Activity module records, audio playback status",
            "no Google Play services or Health Connect client library").forEach {
            assertTrue("Open policy must disclose $it", policy.contains(it))
        }
    }
}
