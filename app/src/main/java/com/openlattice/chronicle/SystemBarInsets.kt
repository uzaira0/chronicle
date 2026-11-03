package com.openlattice.chronicle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps every screen clear of the status bar, action bar, navigation bar, camera cutout and
 * keyboard. From Android 15 (targetSdk 35+) every window is edge-to-edge and the app, not the
 * framework, must inset its content; AppCompat still draws the action bar over the top of the
 * content view. One listener on each activity's content view pads it by the insets it receives
 * and consumes them, so no layout or fragment handles insets itself. Before Android 15 the
 * window already fits the system bars and the listener receives zero insets.
 *
 * Registered once in [ChronicleApplication]; `ScreenLayoutTest` checks every screen against it.
 */
object SystemBarInsets : Application.ActivityLifecycleCallbacks {
    private val TYPES = WindowInsetsCompat.Type.systemBars() or
        WindowInsetsCompat.Type.displayCutout() or
        WindowInsetsCompat.Type.ime()

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(TYPES)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            // The action bar sits outside the content view; keep its up arrow and title out of
            // a landscape camera cutout or side navigation bar.
            activity.findViewById<View>(androidx.appcompat.R.id.action_bar)?.let {
                it.setPadding(bars.left, it.paddingTop, bars.right, it.paddingBottom)
            }
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
