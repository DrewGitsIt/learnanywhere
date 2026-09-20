package com.learnanywhere.app.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Minimal Room schema for persisting the user's study library across restarts.
 * One row per document. The heavy bytes (PDF file, figure bitmaps) live in
 * [AppPdfStore] on disk, keyed by `id`. The row stores just the metadata.
 *
 * Why not store the full text in Room? It would bloat the DB for large papers;
 * we keep the *provenance* + *source* and re-extract on demand (Gemini reads
 * the PDF natively). For plain-text docs (small) we DO store the text.
 */
@Entity(tableName = "documents")
data class DocumentRow(
    @PrimaryKey val id: String,
    @ColumnInfo val title: String,
    @ColumnInfo val source: String,        // "PDF" | "TEXT" | "URL"
    @ColumnInfo val provenance: String,
    @ColumnInfo val text: String,
    @ColumnInfo(name = "sort_order") val order: Int
)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY sort_order ASC")
    fun observe(): Flow<List<DocumentRow>>

    @androidx.room.Query("SELECT * FROM documents WHERE id = :id")
    suspend fun byId(id: String): DocumentRow?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DocumentRow)

    @androidx.room.Delete
    suspend fun delete(row: DocumentRow)
}

@Database(entities = [DocumentRow::class], version = 1, exportSchema = false)
abstract class LearnAnywhereDatabase : androidx.room.RoomDatabase() {
    abstract fun documents(): DocumentDao
}


