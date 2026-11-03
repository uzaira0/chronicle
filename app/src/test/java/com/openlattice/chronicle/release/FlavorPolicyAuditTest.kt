package com.openlattice.chronicle.release

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.capability.DistributionChannel
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FlavorPolicyAuditTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun openPolicyDescribesItsApprovedCapabilities() {
        assumeTrue(DistributionChannel.current() == DistributionChannel.OPEN)
        val policy = context.getString(R.string.platform_privacy_policy_full)
        assertFalse(policy.contains("this non-Play research variant"))
        assertFalse(policy.contains("read-only Health Connect records"))
        assertFalse(policy.contains("media title, artist, and album"))
        assertFalse(policy.contains("whether a call is active"))
    }
}
