package com.bnsoft.chromabox.engine

import java.util.TreeMap

/** A match together with the lens that found it. */
class ScannedMatch<V : Any>(val lens: Lens<V>, val match: LensMatch<V>) {
    val range get() = match.range
}

object LensScanner {
    /**
     * Runs every lens over [text] and drops overlapping matches: the longest match wins, and on ties the lens that
     * comes first in [lenses] wins. The result is sorted by offset.
     */
    fun scan(text: CharSequence, context: LensContext, lenses: List<Lens<*>>): List<ScannedMatch<*>> {
        val perLens = lenses.map { find(it, text, context) }
        if (perLens.size == 1) return perLens.single()

        // Sorting is stable, so on equal length the lens that comes first stays first.
        val candidates = perLens.flatten().sortedByDescending { it.range.length }
        // Accepted matches never overlap, so keyed by start offset they're also ordered by end offset.
        val accepted = TreeMap<Int, ScannedMatch<*>>()
        for (candidate in candidates) {
            val range = candidate.range
            val before = accepted.floorEntry(range.startOffset)?.value
            val after = accepted.ceilingEntry(range.startOffset)?.value
            if (before?.range?.intersectsStrict(range) == true || after?.range?.intersectsStrict(range) == true) continue
            accepted[range.startOffset] = candidate
        }
        return accepted.values.toList()
    }

    private fun <V : Any> find(lens: Lens<V>, text: CharSequence, context: LensContext) =
        lens.find(text, context).map { ScannedMatch(lens, it) }
}
