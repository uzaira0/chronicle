package com.openlattice.chronicle.layout

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import java.util.Locale

/** Geometry checks that hold on any device: text fits, children fit, nothing sits under a system bar. */
object LayoutChecks {
    private const val TOLERANCE_PX = 1

    fun run(root: View, bars: Rect): List<String> {
        val issues = mutableListOf<String>()
        walk(root) { view -> checkView(view, root, bars, issues) }
        // Content at the end of a scrolling screen must also clear the bottom bar once scrolled to it.
        val scrollers = mutableListOf<View>()
        walk(root) { if (it.isShown && (it is ScrollView || it is NestedScrollView)) scrollers += it }
        if (scrollers.isNotEmpty()) {
            scrollers.forEach { it.scrollTo(0, Int.MAX_VALUE / 2) }
            root.layout(root.left, root.top, root.right, root.bottom)
            walk(root) { view -> checkUnderBars(view, root, bars, issues, "scrolled to end") }
            scrollers.forEach { it.scrollTo(0, 0) }
        }
        return issues.distinct()
    }

    fun accessibility(root: View): List<String> {
        val hierarchy = AccessibilityHierarchyAndroid.newBuilder(root).build()
        return AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)
            .flatMap { it.runCheckOnHierarchy(hierarchy) }
            .filter { it.type == AccessibilityCheckResultType.ERROR }
            .map { result ->
                val element = result.element
                val id = element?.resourceName ?: element?.className ?: "?"
                "a11y $id: ${result.getMessage(Locale.ENGLISH)}"
            }
            .distinct()
    }

    private fun checkView(view: View, root: View, bars: Rect, issues: MutableList<String>) {
        if (!view.isShown || view.width == 0 || view.height == 0) return
        // The action bar title is single-line by platform design (Material truncates long top
        // app bar titles); screens must not rely on it for anything the content does not repeat.
        val actionBarTitle = view.parent is Toolbar
        if (view is TextView && view !is EditText && !actionBarTitle && view.text.isNotEmpty()) {
            val layout = view.layout
            if (layout != null) {
                val ellipsized = (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
                if (ellipsized) {
                    issues += "${name(view)} text cut off with … (lines ${layout.lineCount}, max ${view.maxLines}): " +
                        "\"${view.text.take(40)}\""
                }
                val room = view.height - view.totalPaddingTop - view.totalPaddingBottom
                if (layout.height > room + TOLERANCE_PX) {
                    issues += "${name(view)} text taller than its box (${layout.height}px > ${room}px): \"${view.text.take(40)}\""
                }
            }
        }
        val parent = view.parent as? ViewGroup ?: return
        if (parent.clipChildren && !scrollsChildren(parent)) {
            val outside = view.left < -TOLERANCE_PX || view.top < -TOLERANCE_PX ||
                view.right > parent.width + TOLERANCE_PX || view.bottom > parent.height + TOLERANCE_PX
            if (outside) issues += "${name(view)} cut off by its parent ${name(parent)}"
        }
        checkUnderBars(view, root, bars, issues, "at rest")
    }

    private fun checkUnderBars(view: View, root: View, bars: Rect, issues: MutableList<String>, state: String) {
        if (!view.isShown || !(view is TextView || view.isClickable)) return
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible)) return
        val underTop = visible.top < bars.top
        val underBottom = visible.bottom > root.height - bars.bottom
        val underLeft = visible.left < bars.left
        val underRight = visible.right > root.width - bars.right
        if (underTop || underBottom || underLeft || underRight) {
            val where = listOfNotNull(
                "top".takeIf { underTop }, "bottom".takeIf { underBottom },
                "left".takeIf { underLeft }, "right".takeIf { underRight },
            ).joinToString("+")
            issues += "${name(view)} under the $where system bar ($state)"
        }
    }

    private fun scrollsChildren(parent: ViewGroup) =
        parent is ScrollView || parent is NestedScrollView || parent is HorizontalScrollView || parent is RecyclerView

    private fun name(view: View): String {
        val id = if (view.id != View.NO_ID) runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() else null
        return "${view.javaClass.simpleName}${id?.let { "#$it" } ?: ""}"
    }

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
    }
}
