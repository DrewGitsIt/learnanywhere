package com.learnanywhere.core

/**
 * Pure (Android-free) helpers for the (a) figure-extraction feature, extracted
 * so a JVM unit test can drive the exact logic without touching
 * [android.graphics.Bitmap].
 *
 *  - [imageRatioScore] : fraction of "non-white" pixels in a sampled grid — the
 *    heuristic that flags a page as a "figure candidate."
 *  - [captionPrompt]   : the exact prompt we send to Gemini (vision) for a
 *    figure caption.
 */
object Figures {

    /**
     * Grid sample + count of "non-white" pixels.
     *
     * `w`/`h` are the bitmap's pixel dimensions; `pixelAt` is a callback
     * (x, y) -> RGB triple. On the real device this closure calls
     * [android.graphics.Bitmap.getPixel]; in the JVM test it's a synthetic
     * function. The math (stride, threshold, score) is the *same code* on both.
     */
    fun imageRatioScore(w: Int, h: Int, pixelAt: (Int, Int) -> Int): Float {
        val wStep = (w / 48).coerceAtLeast(1)
        val hStep = (h / 48).coerceAtLeast(1)
        var samples = 0; var nonWhite = 0
        for (y in 0 until h step hStep) {
            for (x in 0 until w step wStep) {
                val c = pixelAt(x, y)
                samples++
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (totalDeviationFromWhite(r, g, b) >= 30) nonWhite++
            }
        }
        return if (samples == 0) 0f else nonWhite.toFloat() / samples
    }

    fun totalDeviationFromWhite(r: Int, g: Int, b: Int): Int =
        Math.abs(r - 255) + Math.abs(g - 255) + Math.abs(b - 255)

    /**
     * The exact prompt sent to Gemini (image input) to caption a figure.
     * Kept here (pure) so a test can assert the wording stays consistent.
     */
    fun captionPrompt(docTitle: String, figureTitle: String): String =
        "You are a careful figure-captioner. The user is studying \"$docTitle\". " +
                "The next message contains the figure ($figureTitle). " +
                "Reply with ONE short, self-contained sentence captioning that figure. " +
                "No page numbers, no document-type preamble."
}
