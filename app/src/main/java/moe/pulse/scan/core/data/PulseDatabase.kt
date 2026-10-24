package moe.pulse.scan.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Comma-separated tag list. */
    val tags: String,
    val createdAt: Long,
    val pageCount: Int,
    /** Path of a small unencrypted JPEG thumbnail used by the grid. */
    val coverPath: String,
    /** Concatenated OCR text of all pages, powers full-text search. */
    val ocrText: String,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("docId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val order: Int,
    /** AES-GCM encrypted page image path (filesDir/pages). */
    val path: String,
    val width: Int,
    val height: Int,
)

data class DocumentWithPages(
    val document: DocumentEntity,
    val pages: List<PageEntity>,
)

@Dao
interface ArchiveDao {

    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query(
        "SELECT * FROM documents WHERE title LIKE '%' || :q || '%' " +
            "OR tags LIKE '%' || :q || '%' OR ocrText LIKE '%' || :q || '%' " +
            "ORDER BY createdAt DESC"
    )
    fun search(q: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun document(id: Long): DocumentEntity?

    @Query("SELECT * FROM pages WHERE docId = :docId ORDER BY `order` ASC")
    suspend fun pagesOf(docId: Long): List<PageEntity>

    @Insert
    suspend fun insertDocument(document: DocumentEntity): Long

    @Insert
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Long)

    @Query("UPDATE documents SET title = :title WHERE id = :id")
    suspend fun renameDocument(id: Long, title: String)
}

@Database(entities = [DocumentEntity::class, PageEntity::class], version = 1, exportSchema = false)
abstract class PulseDatabase : RoomDatabase() {

    abstract fun archiveDao(): ArchiveDao

    companion object {
        @Volatile
        private var instance: PulseDatabase? = null

        fun get(context: Context): PulseDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PulseDatabase::class.java,
                    "pulse.db",
                ).build().also { instance = it }
            }
    }
}
