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

/** One saved conversation (a "session"). */
@Entity(tableName = "conversations")
data class ConversationRow(
    @PrimaryKey val id: String,
    @ColumnInfo val title: String,                       // first question, truncated
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/** One turn inside a conversation. */
@Entity(tableName = "messages")
data class MessageRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo val role: String,                        // "user" | "model"
    @ColumnInfo val text: String,
    /** Title (not id) — documents can be deleted independently of chats. */
    @ColumnInfo(name = "cited_doc_title") val citedDocTitle: String?,
    @ColumnInfo(name = "cited_figure") val citedFigure: String?,
    @ColumnInfo val sources: String?,                    // newline-joined
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun observe(): Flow<List<ConversationRow>>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ConversationRow)

    @Query("SELECT * FROM messages WHERE conversation_id = :cid ORDER BY created_at ASC")
    suspend fun messages(cid: String): List<MessageRow>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(msg: MessageRow)

    @Query("DELETE FROM messages WHERE conversation_id = :cid")
    suspend fun deleteMessages(cid: String)

    @Query("DELETE FROM conversations WHERE id = :cid")
    suspend fun deleteConversation(cid: String)
}

@Database(
    entities = [DocumentRow::class, ConversationRow::class, MessageRow::class],
    version = 2,
    exportSchema = false
)
abstract class LearnAnywhereDatabase : androidx.room.RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun conversations(): ConversationDao
}


