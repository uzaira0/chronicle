package com.openlattice.chronicle.ui

import android.content.Context

/**
 * Resolves a string resource id plus format arguments to display text. Pure presenters keep a
 * JVM-testable English table as their default resolver; screens pass [Context.copyResolver] so
 * the same presenter follows the device locale.
 */
typealias CopyResolver = (id: Int, args: Array<out Any>) -> String

fun Context.copyResolver(): CopyResolver = { id, args -> getString(id, *args) }

/** English fallback table for a presenter, keyed by resource id. */
fun englishCopy(table: Map<Int, String>): CopyResolver = { id, args ->
    val template = table[id] ?: error("no English copy registered for resource $id")
    if (args.isEmpty()) template else String.format(template, *args)
}
