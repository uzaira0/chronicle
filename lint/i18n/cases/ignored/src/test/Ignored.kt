package cases

import android.widget.TextView

// Anything under src/test/ is ignored.
class Ignored(private val view: TextView) {
    fun show() {
        view.text = "Prose in a unit test is fine"
    }
}
