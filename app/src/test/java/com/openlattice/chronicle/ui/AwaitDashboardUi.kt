package com.openlattice.chronicle.ui

import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.openlattice.chronicle.R
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

internal inline fun withHostedFragment(
    fragment: Fragment,
    block: (ActivityController<AppCompatActivity>) -> Unit,
) {
    val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
    controller.get().setTheme(R.style.AppTheme)
    controller.create()
    controller.get().supportFragmentManager.beginTransaction()
        .replace(android.R.id.content, fragment).commitNow()
    controller.start().resume()
    try { block(controller) }
    finally { controller.pause().stop().destroy() }
}

internal fun awaitDashboardUi(attempts: Int = 200, condition: () -> Boolean) {
    repeat(attempts) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        Thread.sleep(10)
    }
    assertTrue("Dashboard did not render the expected state", condition())
}
