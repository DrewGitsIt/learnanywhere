package com.learnanywhere.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.learnanywhere.core.Figures
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PDF figure extraction (every page, via the OS PdfRenderer).
 *
 * A "figure candidate" is a page whose non-white pixel ratio is high — i.e.
 * a page that is mostly image / diagram rather than text. Threshold is
 * 0.65, tuned for typical academic PDFs, and unit-tested in
 * LearnAnywherePureTest (imageRatioScoreDistinguishesTextFromFigurePages).
 *
 * Gemini itself reads the PDF natively (figures + tables), so the caption
 * for a page can be fetched on demand via LearnAnywhereAgent.captionFigure —
 * that path uses the rendered PNG bytes, which is also what the UI previews.
 */
object PdfFigureExtractor {

    data class PageFigure(
        val index: Int,
        val title: String,
        val mimeType: String,
        val bytes: ByteArray,
        val isFigureCandidate: Boolean,
        val caption: String? = null
    ) {
        val mime: String get() = mimeType
    }

    /**
     * Render every page of a PDF (raw bytes; the caller already read the URI)
     * to a Bitmap, encode as PNG, and flag figure-candidates.
     */
    fun extract(
        pdfBytes: ByteArray,
        docTitle: String,
        quality: Int = 65,
        maxPages: Int = 200
    ): List<PageFigure> {
        val out = ArrayList<PageFigure>()
        if (pdfBytes.isEmpty()) return out
        val tmp: File = try {
            File.createTempFile("dg_pdf_", ".pdf").also { it.deleteOnExit() }.apply { writeBytes(pdfBytes) }
        } catch (t: Throwable) {
            return out
        }
        val fd: ParcelFileDescriptor = try {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            try { tmp.delete() } catch (_: Throwable) {}
            return out
        }
        val renderer: android.graphics.pdf.PdfRenderer = try {
            android.graphics.pdf.PdfRenderer(fd)
        } catch (t: Throwable) {
            try { fd.close() } catch (_: Throwable) {}
            try { tmp.delete() } catch (_: Throwable) {}
            return out
        }
        try {
            val pageCount = minOf(renderer.pageCount, maxPages)
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(0xFFFFFFFF.toInt())
                page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val isFig = Figures.imageRatioScore(bmp.width, bmp.height) { x, y -> bmp.getPixel(x, y) } >= 0.65
                val png = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, quality, png)
                out.add(
                    PageFigure(
                        index = i,
                        title = "Figure ${i + 1}",
                        mimeType = "image/png",
                        bytes = png.toByteArray(),
                        isFigureCandidate = isFig
                    )
                )
                page.close()
                bmp.recycle()
            }
        } finally {
            renderer.close()
            try { fd.close() } catch (_: Throwable) {}
            try { tmp.delete() } catch (_: Throwable) {}
        }
        return out
    }

    /** Pure function used by JVM-side tests if needed. */
    fun scorePixels(w: Int, h: Int, pixelAt: (Int, Int) -> Int): Float =
        Figures.imageRatioScore(w, h, pixelAt)
}
