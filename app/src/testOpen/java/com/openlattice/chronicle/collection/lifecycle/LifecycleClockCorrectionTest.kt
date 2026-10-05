package com.openlattice.chronicle.collection.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LifecycleClockCorrectionTest {
    @Test fun futureDedupeTimestampIsReplacedAfterClockCorrection() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(LIFECYCLE_RECORDER_PREFS_NAME, Context.MODE_PRIVATE)
        val event = LifecycleEventMapper.buildEvent("android.intent.action.BATTERY_LOW", "Battery Low", 1L)
        val key = "last:${event.interactionType}:${event.activityClass ?: ""}"
        val now = 1_700_000_000_000L
        prefs.edit().clear().putLong(key, now + 60 * 60 * 1_000L).commit()
        val store = PrefsLifecycleDedupeStore(context)

        assertTrue(store.shouldPersist(event, now))
        assertEquals(now, prefs.getLong(key, Long.MIN_VALUE))
        assertFalse(store.shouldPersist(event, now + 500L))
    }
}
