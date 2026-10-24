package com.griha.inspection.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A leave request made on this phone (DB v7). Like attendance, the row is its own upload queue:
 * saved first, so a request made without signal is never lost, and sent at the next sync. Once
 * the server has it ([sentAt]), the server's copy is the truth (status, admin's note); this row
 * only remembers that it was sent.
 */
@Entity(tableName = "leave_requests", indices = [Index(value = ["user_id", "sent_at"])])
data class LeaveRequestEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    /** CASUAL | SICK | COMP_OFF */
    val kind: String,
    /** yyyy-MM-dd, inclusive. */
    @ColumnInfo(name = "start_day") val startDay: String,
    @ColumnInfo(name = "end_day") val endDay: String,
    /** FIRST | SECOND for a half day (single day only); null = full days. */
    val half: String?,
    val reason: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "sent_at") val sentAt: Long? = null,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long = 0,
    /** Set when the server refused it for good (e.g. overlapping leave): shown, never retried. */
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "failed_at") val failedAt: Long? = null,
)

@Dao
interface LeaveDao {
    @Insert
    suspend fun insert(row: LeaveRequestEntity)

    @Query("SELECT * FROM leave_requests WHERE id = :id")
    suspend fun get(id: String): LeaveRequestEntity?

    /** Requests this phone hasn't delivered yet (waiting, or refused), newest first. */
    @Query("SELECT * FROM leave_requests WHERE user_id = :userId AND sent_at IS NULL ORDER BY created_at DESC")
    fun observeUnsent(userId: String): Flow<List<LeaveRequestEntity>>

    @Query(
        """
        SELECT * FROM leave_requests
        WHERE user_id = :userId AND sent_at IS NULL AND failed_at IS NULL AND next_attempt_at <= :now
        ORDER BY created_at LIMIT 20
        """
    )
    suspend fun due(userId: String, now: Long): List<LeaveRequestEntity>

    @Query("UPDATE leave_requests SET sent_at = :at, last_error = NULL WHERE id = :id")
    suspend fun markSent(id: String, at: Long)

    @Query("UPDATE leave_requests SET attempt_count = attempt_count + 1, next_attempt_at = :next, last_error = :error WHERE id = :id")
    suspend fun retryLater(id: String, next: Long, error: String?)

    @Query("UPDATE leave_requests SET failed_at = :at, last_error = :error WHERE id = :id")
    suspend fun markRefused(id: String, at: Long, error: String)

    /** The engineer dismissed a refused request (it never reached the server). */
    @Query("DELETE FROM leave_requests WHERE id = :id AND sent_at IS NULL AND failed_at IS NOT NULL")
    suspend fun dismissRefused(id: String)

    @Query("UPDATE leave_requests SET next_attempt_at = 0 WHERE sent_at IS NULL AND failed_at IS NULL")
    suspend fun retryAllNow()

    /** When this user's next waiting request may be sent (wakes the sync job), or null. */
    @Query("SELECT MIN(next_attempt_at) FROM leave_requests WHERE user_id = :userId AND sent_at IS NULL AND failed_at IS NULL")
    suspend fun nextDueAt(userId: String): Long?

    /** Delivered requests only remember that they were sent; the server holds them. */
    @Query("DELETE FROM leave_requests WHERE sent_at IS NOT NULL AND sent_at < :before")
    suspend fun deleteSentBefore(before: Long): Int
}
