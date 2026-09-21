package com.learnanywhere.app.db

import android.content.Context
import androidx.room.Room
import com.learnanywhere.data.Document

/**
 * Thin facade over [LearnAnywhereDatabase]. The app holds one instance; the
 * DocumentStore and the Compose UI both call into it.
 */
class AppDatabase(private val context: Context) {

    val db: LearnAnywhereDatabase = Room.databaseBuilder(
        context,
        LearnAnywhereDatabase::class.java,
        "learnanywhere.db"
    ).addMigrations(MIGRATION_1_2).build()
    // No fallbackToDestructiveMigration: a botched migration must fail loudly,
    // not silently wipe the user's library.

    companion object {
        /** v1 → v2: conversation sessions (conversations + messages tables). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversations` (" +
                            "`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (" +
                            "`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, " +
                            "`role` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                            "`cited_doc_title` TEXT, `cited_figure` TEXT, `sources` TEXT, " +
                            "`created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
    }

    val pdfStore: AppPdfStore = AppPdfStore(context)

    // ------------------------------------------------------------------
    // Row <-> Document mapping
    // ------------------------------------------------------------------

    /**
     * Turn a [DocumentRow] + any persisted PDF bytes / figures into a
     * fully-hydrated [Document]. For the MVP: text docs and URL docs come
     * straight back; PDFs get their text re-attached from the PDF bytes we
     * saved at add-time (we stored the *extracted* text row; see add-time
     * path). Figures are re-read from [AppPdfStore].
     */
    fun toDocument(row: DocumentRow, figures: List<com.learnanywhere.data.Figure> = emptyList()): Document {
        val pdfLocator = when (row.source) {
            "PDF"  -> pdfStore.locatorFor(row.id)
            else   -> null
        }
        return Document(
            id = row.id, title = row.title,
            source = row.source.let { s ->
                enumValues<com.learnanywhere.data.Document.Source>().first { it.name == s }
            },
            pdfLocator = pdfLocator,
            text = row.text,
            provenance = row.provenance,
            figures = figures,
            order = row.order
        )
    }
}
