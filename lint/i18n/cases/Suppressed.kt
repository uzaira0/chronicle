package cases

import android.widget.TextView

// Suppression semantics: an ignore directive on its own line above the statement is honored,
// with or without a rule id. A -- reason after the rule id is honored, and so is a same-line
// trailing ignore comment (ast-grep >= 0.45.2).
class Suppressed(private val view: TextView) {
    fun show() {
        // ast-grep-ignore: android-i18n-ui-literal
        view.text = "Not set"
        // ast-grep-ignore
        view.text = "Blanket ignore also works"
        // ast-grep-ignore: android-i18n-ui-literal -- reason text
        view.text = "Still reported"
        view.text = "Same-line ignore is honored" // ast-grep-ignore: android-i18n-ui-literal
    }
}
