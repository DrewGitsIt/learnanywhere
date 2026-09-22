package com.learnanywhere.core

/**
 * Sorting/merging for the char ranges a skip-map is made of (DESIGN §7.2).
 *
 * Skip ranges are INCLUSIVE (`first..last`) and arrive from several places —
 * regex heuristics, LLM-located quotes, a JSON sidecar written by an older
 * build — so every consumer wants the same thing: in bounds, sorted, merged,
 * non-overlapping. That normalization lives here so [Sections], [Boilerplate]
 * and the store can't disagree about it.
 */
object Ranges {

    /** [ranges] clamped to `0 until length`, sorted, merged, empties dropped. */
    fun normalize(ranges: List<IntRange>, length: Int): List<IntRange> {
        if (length <= 0 || ranges.isEmpty()) return emptyList()
        val clamped = ranges.mapNotNull { r ->
            // A reversed range is dropped, never repaired by swapping: it means
            // a boundary quote landed out of order (or a sidecar is corrupt),
            // and guessing what was meant is how prose gets muted.
            if (r.first > r.last) return@mapNotNull null
            val first = maxOf(0, r.first)
            val last = minOf(length - 1, r.last)
            if (first > last) null else first..last
        }.sortedWith(compareBy({ it.first }, { it.last }))
        if (clamped.isEmpty()) return emptyList()

        val out = ArrayList<IntRange>(clamped.size)
        var cur = clamped[0]
        for (r in clamped.drop(1)) {
            // Adjacent counts as overlapping: two ranges that touch describe
            // one hole, and leaving the seam would emit an empty fragment.
            cur = if (r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last)
            else { out.add(cur); r }
        }
        out.add(cur)
        return out
    }

    /** Total chars covered by [ranges] (assumed already normalized). */
    fun span(ranges: List<IntRange>): Int = ranges.sumOf { it.last - it.first + 1 }
}
