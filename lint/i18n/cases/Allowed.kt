package cases

import android.content.Context
import android.util.Log
import android.widget.TextView
import android.widget.Toast

// Allowed shapes for android-i18n-ui-literal: resources, variables, templates without literal
// words, wire values, developer diagnostics. Nothing here may fire.
class Allowed(private val context: Context, private val view: TextView, private val n: Int) {
    fun show(label: String) {
        Toast.makeText(context, R.string.upload_complete, Toast.LENGTH_SHORT).show()
        Toast.makeText(context, context.getString(R.string.upload_complete), Toast.LENGTH_SHORT).show()
        Toast.makeText(context, context.getString(R.string.uploaded_count, n), Toast.LENGTH_SHORT).show()
        view.text = context.getString(R.string.waiting_for_consent)
        view.text = label
        view.text = "$label: 42"
        view.text = "${label}"
        view.text = ""
        view.text = "42"
        view.text = "N/A"
        view.text = null
        view.hint = null
        view.text = if (n > 0) "many" else "none"
        view.text = context.resources.getQuantityString(R.plurals.items, n, n)
        Log.d("Allowed", "English log text is not user visible")
        require(n >= 0) { "Developer invariant text" }
        check(label.isNotEmpty()) { "Developer check text" }
        error("Developer failure text")
        val wire = "Not set"
        view.tag = "Tags are not user visible"
    }
}
// Documented hole: a literal chosen by an if/else expression (`if (x) "many" else "none"`) is not
// matched, because only a direct string argument is inspected.
