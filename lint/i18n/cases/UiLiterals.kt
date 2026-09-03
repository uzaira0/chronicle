package cases

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import com.google.android.material.snackbar.Snackbar

// Cases for android-i18n-ui-literal. A `FIRE:` marker names the finding expected on that line;
// unmarked lines must be clean. A chained builder call is reported on the line where the chain
// starts, once per matching setter.
class UiLiterals(private val context: Context, private val view: TextView, private val n: Int) {
    var title: String = ""
    var subtitle: String = ""
    var summary: String = ""

    fun show() {
        Toast.makeText(context, "Upload complete", Toast.LENGTH_SHORT).show() // FIRE: android-i18n-ui-literal
        Toast.makeText( // FIRE: android-i18n-ui-literal
            context,
            "Saved successfully",
            Toast.LENGTH_LONG,
        ).show()
        Snackbar.make(view, "Undo the change", Snackbar.LENGTH_SHORT).show() // FIRE: android-i18n-ui-literal
        view.text = "Waiting for consent" // FIRE: android-i18n-ui-literal
        view.text = "Uploaded $n items" // FIRE: android-i18n-ui-literal
        view.text = "OK" // FIRE: android-i18n-ui-literal
        view.text = "Prefix: " + n // FIRE: android-i18n-ui-literal
        view.hint = "Participant id" // FIRE: android-i18n-ui-literal
        view.contentDescription = "Toggle module" // FIRE: android-i18n-ui-literal
        view.error = "Required field" // FIRE: android-i18n-ui-literal
        view.setText("Set via setter") // FIRE: android-i18n-ui-literal
        view.setHint("Hint via setter") // FIRE: android-i18n-ui-literal
        view.setContentDescription("Description via setter") // FIRE: android-i18n-ui-literal
        this.title = "Activity title" // FIRE: android-i18n-ui-literal
        this.subtitle = "Activity subtitle" // FIRE: android-i18n-ui-literal
        this.summary = "Preference summary" // FIRE: android-i18n-ui-literal
        AlertDialog.Builder(context) // FIRE: android-i18n-ui-literal, android-i18n-ui-literal, android-i18n-ui-literal
            .setTitle("Delete data?")
            .setMessage("This cannot be undone")
            .setPositiveButton("Delete") { _, _ -> }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(context.getString(R.string.later), null)
            .show()
        AlertDialog.Builder(context).setNegativeButton("Keep", null).setNeutralButton("Later", null).show() // FIRE: android-i18n-ui-literal, android-i18n-ui-literal
        NotificationCompat.Builder(context, "reminders") // FIRE: android-i18n-ui-literal, android-i18n-ui-literal, android-i18n-ui-literal, android-i18n-ui-literal
            .setContentTitle("Study reminder")
            .setContentText("Please complete the survey")
            .setSubText("Study team")
            .setTicker("Reminder posted")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Long reminder body")) // FIRE: android-i18n-ui-literal
            .build()
        NotificationCompat.BigTextStyle().setBigContentTitle("Big title").setSummaryText("Summary text") // FIRE: android-i18n-ui-literal, android-i18n-ui-literal
        NotificationChannel("reminders", "Study reminders", NotificationManager.IMPORTANCE_DEFAULT) // FIRE: android-i18n-ui-literal
        view.text = """Raw prose here""" // FIRE: android-i18n-ui-literal
        title = "Bare title assignment" // FIRE: android-i18n-ui-literal
        AlertDialog.Builder(context) // FIRE: android-i18n-ui-literal, android-i18n-ui-literal
            // a comment inside the chain does not hide the setters
            .setTitle("Comment above") // nor does a trailing one
            .setMessage("Comment between")
            .show()
    }
}
