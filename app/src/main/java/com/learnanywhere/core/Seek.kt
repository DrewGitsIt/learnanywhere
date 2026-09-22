package com.learnanywhere.core

/**
 * Voice seek's last mile (DESIGN §7.3): a char offset in the stored document
 * text — where [TextLocate] found the agent's `seek_quote` — becomes the
 * read-with-me section the listener should be dropped into.
 *
 * Deliberately tiny and deliberately total. The offset arrives from a model
 * quote matched against a document whose sectioning may have been rebuilt with
 * a newer skip-map in between, so "no section starts at or before this offset"
 * and "this offset is past the last section" are both ordinary outcomes, not
 * errors: the first reads as the beginning, the second as the final section.
 * A seek that throws would cost the user the spoken answer too.
 */
object Seek {

    /**
     * Index of the section containing [offset]: the largest index whose
     * [Sections.Section.start] is at or before it.
     *
     * Returns 0 for an empty list, for a negative offset, and for an offset
     * before the first section's start (boilerplate skipped from the head
     * leaves exactly that gap). Scans rather than binary-searches so unsorted
     * input still yields the best candidate instead of nonsense — sections
     * number in the tens, and this runs once per jump.
     */
    fun sectionIndexFor(sections: List<Sections.Section>, offset: Int): Int {
        var best = 0
        var bestStart = Int.MIN_VALUE
        for (i in sections.indices) {
            val start = sections[i].start
            if (start <= offset && start >= bestStart) {
                best = i
                bestStart = start
            }
        }
        return best
    }
}
