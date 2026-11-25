package co.yehaw.browser.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TabStateDao {
    @Query("SELECT * FROM tab_state ORDER BY position ASC")
    abstract suspend fun getAll(): List<TabStateEntity>

    @Query("SELECT * FROM tab_state WHERE id = :id")
    abstract suspend fun get(id: String): TabStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: TabStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entities: List<TabStateEntity>)

    @Query("DELETE FROM tab_state WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("DELETE FROM tab_state")
    abstract suspend fun deleteAll()

    @Query("UPDATE tab_state SET webState = NULL WHERE containerId = :containerId")
    abstract suspend fun clearWebState(containerId: String)

    @Query("UPDATE tab_state SET webState = NULL")
    abstract suspend fun clearAllWebState()

    @Transaction
    open suspend fun replaceAll(entities: List<TabStateEntity>) {
        deleteAll()
        upsertAll(entities)
    }
}

@Dao
interface ContainerDao {
    @Query("SELECT * FROM containers ORDER BY createdAt ASC")
    suspend fun getAll(): List<ContainerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ContainerEntity)

    @Query("DELETE FROM containers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ArchiveDao {
    @Query("SELECT * FROM archives ORDER BY savedAt DESC")
    suspend fun getAll(): List<ArchiveEntity>

    @Query("SELECT * FROM archives ORDER BY savedAt DESC LIMIT :n")
    suspend fun latest(n: Int): List<ArchiveEntity>

    @Query("SELECT * FROM archives WHERE id = :id")
    suspend fun get(id: Long): ArchiveEntity?

    @Insert
    suspend fun insert(entity: ArchiveEntity): Long

    @Query("DELETE FROM archives WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state IN (:states) ORDER BY createdAt ASC")
    suspend fun getByStates(states: List<Int>): List<DownloadEntity>

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    /** Only touches rows still RUNNING so a late progress write can never undo a pause or cancel. */
    @Query("UPDATE downloads SET receivedBytes = :received, totalBytes = :total WHERE id = :id AND state = 1")
    suspend fun updateProgress(id: Long, received: Long, total: Long)

    @Query("UPDATE downloads SET receivedBytes = 0, state = 0 WHERE id = :id")
    suspend fun resetForRestart(id: Long)

    @Query("UPDATE downloads SET state = :state, error = :error WHERE id = :id")
    suspend fun updateState(id: Long, state: Int, error: String? = null)

    @Query("UPDATE downloads SET etag = :etag, lastModified = :lastModified, mimeType = COALESCE(mimeType, :mime) WHERE id = :id")
    suspend fun updateValidators(id: Long, etag: String?, lastModified: String?, mime: String?)

    @Query("UPDATE downloads SET retryCount = :n WHERE id = :id")
    suspend fun updateRetry(id: Long, n: Int)

    @Query("UPDATE downloads SET state = 4, savedUri = :uri, cookie = NULL, error = NULL, receivedBytes = totalBytes WHERE id = :id")
    suspend fun markCompleted(id: Long, uri: String?)

    @Query("UPDATE downloads SET cookie = NULL WHERE id = :id")
    suspend fun wipeCookie(id: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE state IN (4, 5, 6)")
    suspend fun deleteFinished()

    @Query("UPDATE downloads SET state = 0 WHERE state = 1")
    suspend fun requeueStaleRunning()
}
