package com.learnanywhere.ui

/**
 * Pure presentation helpers for the read-along surfaces (DESIGN §6.4/§6.5).
 *
 * They are here, free of Compose and Android, because they are the parts that
 * can actually be wrong — an off-by-one into `figures`, a highlight anchored to
 * the wrong occurrence — and so the parts worth unit-testing.
 */

/**
 * Index range of [spoken] inside [display], or null when there is nothing to
 * highlight. `first` is the start offset, `last` the LAST highlighted index —
 * callers passing this to `AnnotatedString` want `last + 1` as the end.
 *
 * The spoken text is the sentence verbatim (the player is fed the same string
 * the bubble renders), so a plain search is enough; whitespace-only or absent
 * matches yield null rather than a bogus span.
 */
fun highlightRange(display: String, spoken: String): IntRange? {
    val needle = spoken.trim()
    if (needle.isEmpty() || display.isEmpty()) return null
    val at = display.indexOf(needle)
    if (at < 0) return null
    return at..(at + needle.length - 1)
}

/**
 * The model supplies `cited_page` unvalidated, so clamp before it indexes
 * anything. Returns null when there is no page or nothing to index into;
 * otherwise a 1-based page within `1..pageCount`.
 */
fun clampCitedPage(page: Int?, pageCount: Int): Int? {
    if (page == null || pageCount <= 0) return null
    return page.coerceIn(1, pageCount)
}

/**
 * Resolve a cited document title against the library: exact match first, then
 * either-way containment (the model paraphrases titles, and the stored title
 * may carry a file extension the citation drops). Returns null when ambiguous
 * input can't be pinned to a row.
 */
fun resolveCitedDocIndex(titles: List<String>, cited: String?): Int? {
    val want = cited?.trim().orEmpty()
    if (want.isEmpty()) return null
    val exact = titles.indexOfFirst { it.equals(want, ignoreCase = true) }
    if (exact >= 0) return exact
    val loose = titles.indexOfFirst {
        it.isNotBlank() &&
            (it.contains(want, ignoreCase = true) || want.contains(it, ignoreCase = true))
    }
    return loose.takeIf { it >= 0 }
}

/** Speed chip label: "1×", "1.25×" — no trailing Kotlin float noise. */
fun speedLabel(r: Float): String =
    if (r == r.toInt().toFloat()) "${r.toInt()}×" else "$r×"
