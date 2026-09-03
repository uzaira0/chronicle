package com.openlattice.chronicle

import android.view.View
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun AppCompatActivity.padViewForSystemBars(@IdRes viewId: Int, top: Boolean = false) {
    findViewById<View>(viewId)?.padForSystemBars(top)
}

/**
 * Insets [this] for the system bars. By default only the left/right/bottom bars are padded
 * (the top is reserved for an ActionBar or a layout's own top padding). Pass [top] = true on
 * the topmost container of a screen that has NO ActionBar (e.g. MainActivity's NoActionBar
 * root) so its content clears the status bar instead of drawing underneath it — required
 * under the edge-to-edge enforced from targetSdk 35+.
 */
fun View.padForSystemBars(top: Boolean = false) {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            initialLeft + systemBars.left,
            if (top) initialTop + systemBars.top else initialTop,
            initialRight + systemBars.right,
            initialBottom + systemBars.bottom,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
