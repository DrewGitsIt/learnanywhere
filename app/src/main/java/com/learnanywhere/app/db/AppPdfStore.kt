package com.learnanywhere.app.db

import android.content.Context
import java.io.File

/**
 * On-disk byte store for the heavy parts of a document (the PDF file itself,
 * plus its rendered figure bitmaps). Keyed by document id.
 *
 * We write:
 *   <dataDir>/pdfs/<id>.pdf      (the original PDF bytes)
 *   <dataDir>/figs/<id>_<n>.png  (rendered page bitmaps)
 *
 * Room keeps only the metadata row; bytes live here. On cold start,
 * [DocumentStore.hydrate] re-attaches them via the `pdfBytesStore` lambda.
 */
class AppPdfStore(private val context: Context) {

    private val pdfDir: File by lazy {
        File(context.filesDir.absolutePath, "pdfs").apply { mkdirs() }
    }
    private val figDir: File by lazy {
        File(context.filesDir.absolutePath, "figs").apply { mkdirs() }
    }

    fun writePdf(id: String, bytes: ByteArray) { File(pdfDir, "$id.pdf").writeBytes(bytes) }
    fun readPdf(id: String): ByteArray =
        File(pdfDir, "$id.pdf").takeIf { it.exists() }?.readBytes() ?: ByteArray(0)

    fun writeFigure(id: String, index: Int, bytes: ByteArray) {
        File(figDir, "${id}_$index.png").writeBytes(bytes)
    }
    fun readFigure(id: String, index: Int): ByteArray =
        File(figDir, "${id}_$index.png").takeIf { it.exists() }?.readBytes() ?: ByteArray(0)

    private fun figuresMatching(id: String): Array<File> {
        val f = object : java.io.FileFilter {
            override fun accept(file: File): Boolean = file.name.startsWith("${id}_")
        }
        return figDir.listFiles(f) ?: emptyArray()
    }

    fun figureCount(id: String): Int = figuresMatching(id).size

    fun figuresFor(id: String): List<ByteArray> {
        val files = figuresMatching(id).sortedBy { it.name }
        return files.map { it.readBytes() }
    }

    fun locatorFor(id: String): String {
        // "file://" + real path. Consumers (LearnAnywhereAgent.loadPdf, etc.) parse
        // this with Uri.parse and read it from the filesystem.
        return "file://" + File(pdfDir, "$id.pdf").absolutePath
    }

    fun deleteAll(id: String) {
        File(pdfDir, "$id.pdf").delete()
        figuresMatching(id).forEach { it.delete() }
    }
}
