package com.openlattice.chronicle.layout

import android.content.DialogInterface
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.openlattice.chronicle.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParticipantThemeTest {
    @Test
    @OptIn(ExperimentalRoborazziApi::class)
    fun darkConfirmationButtonsHaveReadablePrimaryText() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        val activity = controller.get()
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.disclosure_before_you_join)
            .setMessage(R.string.disclosure_eligibility_attestation)
            .setPositiveButton(R.string.disclosure_agree, null)
            .setNegativeButton(R.string.disclosure_cancel, null)
        val dialog = builder.show()
        try {
            shadowOf(Looper.getMainLooper()).idleFor(32, TimeUnit.MILLISECONDS)
            val background = checkNotNull((builder.background as MaterialShapeDrawable).fillColor).defaultColor
            for (button in listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE)) {
                val action = dialog.getButton(button)
                assertTrue("Dialog actions must be visible", action.isShown && action.width > 0 && action.height > 0)
                assertTrue("Dialog actions need 4.5:1 contrast in dark mode",
                    ColorUtils.calculateContrast(action.currentTextColor, background) >= 4.5)
            }
            captureScreenRoboImage("src/testOpen/screenshots/confirmation_phone_dark_en.png")
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }
}
