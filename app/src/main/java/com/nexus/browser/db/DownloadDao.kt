package com.nexus.browser.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    // ── Observe (reactive) ────────────────────────────────────────────────────

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query(
        "SELECT * FROM downloads WHERE status IN (:queued, :running, :waitingForNetwork) ORDER BY createdAt DESC"
    )
    fun observeActive(
        queued: Int = DownloadStatus.QUEUED,
        running: Int = DownloadStatus.RUNNING,
        waitingForNetwork: Int = DownloadStatus.WAITING_FOR_NETWORK
    ): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeById(id: Long): Flow<DownloadEntity?>

    // ── One-shot reads ────────────────────────────────────────────────────────

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE dmId = :dmId LIMIT 1")
    suspend fun getByDmId(dmId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadEntity>

    /** Rows that were RUNNING or QUEUED or WAITING_FOR_NETWORK when the process last died. */
    @Query(
        "SELECT * FROM downloads WHERE status IN (:running, :queued, :waitingForNetwork)"
    )
    suspend fun getInterruptedDownloads(
        running: Int = DownloadStatus.RUNNING,
        queued: Int = DownloadStatus.QUEUED,
        waitingForNetwork: Int = DownloadStatus.WAITING_FOR_NETWORK
    ): List<DownloadEntity>

    // ── Writes ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity): Long

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("UPDATE downloads SET dmId = :dmId WHERE id = :id")
    suspend fun updateDmId(id: Long, dmId: Long)

    /** High-frequency progress tick written by DownloadService while actively downloading. */
    @Query(
        """
        UPDATE downloads
        SET progress = :progress,
            status = :status,
            totalBytes = :totalBytes,
            bytesDownloaded = :bytesDownloaded,
            speedBps = :speedBps,
            etaMillis = :etaMillis,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: Long,
        progress: Int,
        status: Int,
        totalBytes: Long,
        bytesDownloaded: Long,
        speedBps: Long,
        etaMillis: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE downloads SET status = :status, savePath = :savePath, progress = 100, speedBps = 0, etaMillis = 0, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateCompleted(
        id: Long,
        savePath: String,
        status: Int = DownloadStatus.COMPLETED,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE downloads SET status = :status, speedBps = 0, etaMillis = -1, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateFailed(
        id: Long,
        errorMessage: String,
        status: Int = DownloadStatus.FAILED,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE downloads SET status = :status, speedBps = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: Int, updatedAt: Long = System.currentTimeMillis())

    @Query(
        "UPDATE downloads SET status = :status, partFilePath = :partFilePath, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updatePartFilePath(
        id: Long,
        partFilePath: String,
        status: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE downloads SET retryCount = retryCount + 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun incrementRetryCount(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET supportsResume = :supportsResume WHERE id = :id")
    suspend fun updateSupportsResume(id: Long, supportsResume: Boolean)

    // ── Deletes ───────────────────────────────────────────────────────────────

    @Delete
    suspend fun delete(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "DELETE FROM downloads WHERE status IN (:completed, :failed, :cancelled)"
    )
    suspend fun clearFinished(
        completed: Int = DownloadStatus.COMPLETED,
        failed:    Int = DownloadStatus.FAILED,
        cancelled: Int = DownloadStatus.CANCELLED
    )

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
}
