package com.openlattice.chronicle.collection.device

import android.content.Context
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Replaces the public module factory while collection and storage remain real. */
@Implements(value = HealthMetricModuleHolder::class, isInAndroidSdk = false)
class HealthMetricModuleHolderTestShadow {
    @Implementation fun get(context: Context): HealthMetricCollectionModule {
        check(context === context.applicationContext)
        return checkNotNull(module)
    }

    companion object {
        var module: HealthMetricCollectionModule? = null
    }
}
