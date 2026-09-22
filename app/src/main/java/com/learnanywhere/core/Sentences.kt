package com.learnanywhere.core

/**
 * Sentence-splitting for text that is already complete, as opposed to
 * [SentenceChunker], which does the same job for a stream of deltas.
 *
 * Callers feed the result to `AudiobookPlayer.sayChain`, so the rules must
 * match the streaming voice loop exactly — hence the delegation rather than a
 * second implementation.
 */
object Sentences {

    fun split(text: String, maxLen: Int = 400): List<String> {
        val chunker = SentenceChunker(maxLen)
        val out = ArrayList(chunker.feed(text))
        chunker.flush()?.let { out.add(it) }
        return out
    }
}
