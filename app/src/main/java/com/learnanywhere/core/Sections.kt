package com.learnanywhere.core

/**
 * Pure sectionizer for "read with me" mode: splits a document's text into
 * speakable sections (~[target] chars), preferring paragraph boundaries and
 * falling back to sentence cuts inside giant paragraphs. Unit-tested.
 */
object Sections {

    fun split(text: String, target: Int = 1200): List<String> {
        val paras = text.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (p0 in paras) {
            var p = p0
            // A single paragraph far over target: cut at sentence boundaries.
            while (p.length > target * 2) {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                val cut = sentenceCut(p, target)
                out.add(p.substring(0, cut).trim())
                p = p.substring(cut).trim()
            }
            when {
                sb.isEmpty() -> sb.append(p)
                sb.length < target -> sb.append("\n\n").append(p)
                else -> { out.add(sb.toString()); sb.setLength(0); sb.append(p) }
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Last sentence end at or before [target], else hard cut at target. */
    private fun sentenceCut(s: String, target: Int): Int {
        var best = -1
        for (i in 0 until minOf(s.length - 1, target)) {
            val c = s[i]
            if ((c == '.' || c == '!' || c == '?') && s[i + 1].isWhitespace()) best = i + 1
        }
        return if (best > 0) best else minOf(target, s.length)
    }
}
