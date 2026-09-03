package cases

import android.widget.TextView

// Suppression semantics: an ignore directive on its own line above the statement is honored,
// with or without a rule id. Trailing text after the rule id breaks it, and a same-line
// trailing comment is not consulted.
class Suppressed(private val view: TextView) {
    fun show() {
        // ast-grep-ignore: android-i18n-ui-literal
        view.text = "Not set"
        // ast-grep-ignore
        view.text = "Blanket ignore also works"
        // ast-grep-ignore: android-i18n-ui-literal -- reason text breaks it FIRE: unused-suppression
        view.text = "Still reported" // FIRE: android-i18n-ui-literal
        view.text = "Same-line ignore is not honored" // ast-grep-ignore: android-i18n-ui-literal FIRE: android-i18n-ui-literal, unused-suppression
    }
}
