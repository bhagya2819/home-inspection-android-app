package com.homeinspection.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One check-in or check-out (feature 1, DB v5). Append-only: never edited after it is saved, so
 * it needs no revisions or conflicts, and the row itself is its upload queue ([sentAt],
 * [selfieVerifiedAt]) — written in the same insert, so it can never exist without being queued.
 */
@Entity(
    tableName = "attendance",
    indices = [Index(value = ["user_id", "client_at"]), Index("next_attempt_at")],
)
data class AttendanceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    /** IN or OUT. */
    val kind: String,
    @ColumnInfo(name = "client_at") val clientAt: Long,
    @ColumnInfo(name = "time_zone") val timeZone: String,
    /** SystemClock.elapsedRealtime() and the boot counter at the event: the server's clock check. */
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long,
    @ColumnInfo(name = "boot_count") val bootCount: Long?,
    val lat: Double? = null,
    val lng: Double? = null,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float? = null,
    @ColumnInfo(name = "location_at") val locationAt: Long? = null,
    @ColumnInfo(name = "is_mock") val isMock: Boolean = false,
    /** A [com.homeinspection.app.core.face.FaceResult] name. */
    @ColumnInfo(name = "face_result") val faceResult: String,
    @ColumnInfo(name = "face_score") val faceScore: Float? = null,
    @ColumnInfo(name = "enrollment_id") val enrollmentId: String? = null,
    @ColumnInfo(name = "liveness_passed") val livenessPassed: Boolean,
    /** Relative to filesDir; null once the server verified it and the phone copy was removed. */
    @ColumnInfo(name = "selfie_path") val selfiePath: String?,
    @ColumnInfo(name = "selfie_sha256") val selfieSha256: String,
    @ColumnInfo(name = "selfie_size") val selfieSize: Long,
    /** Server stored the record. */
    @ColumnInfo(name = "sent_at") val sentAt: Long? = null,
    /** Server verified the selfie's bytes (SHA-256). */
    @ColumnInfo(name = "selfie_verified_at") val selfieVerifiedAt: Long? = null,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    val isIn get() = kind == KIND_IN
    val uploaded get() = sentAt != null && selfieVerifiedAt != null

    companion object {
        const val KIND_IN = "IN"
        const val KIND_OUT = "OUT"
    }
}

@Dao
interface AttendanceDao {
    @Insert
    suspend fun insert(row: AttendanceEntity)

    @Query("SELECT * FROM attendance WHERE id = :id")
    suspend fun get(id: String): AttendanceEntity?

    /** When this user's next record or selfie may be uploaded (wakes the sync job), or null. */
    @Query("SELECT MIN(next_attempt_at) FROM attendance WHERE user_id = :userId AND (sent_at IS NULL OR selfie_verified_at IS NULL)")
    suspend fun nextDueAt(userId: String): Long?

    /** The engineer's latest check-in/out on this phone: decides "on duty" and the reminder. */
    @Query("SELECT * FROM attendance WHERE user_id = :userId ORDER BY client_at DESC LIMIT 1")
    fun observeLatest(userId: String): Flow<AttendanceEntity?>

    @Query("SELECT * FROM attendance WHERE user_id = :userId AND client_at >= :from AND client_at < :to ORDER BY client_at DESC")
    fun observeRange(userId: String, from: Long, to: Long): Flow<List<AttendanceEntity>>

    /**
     * [userId]'s records or selfies the server doesn't have yet, whose retry time has come. Only the
     * signed-in engineer's: another person's offline check-in on a shared phone waits for them.
     */
    @Query(
        """
        SELECT * FROM attendance
        WHERE user_id = :userId AND (sent_at IS NULL OR selfie_verified_at IS NULL) AND next_attempt_at <= :now
        ORDER BY client_at LIMIT :limit
        """
    )
    suspend fun due(userId: String, now: Long, limit: Int): List<AttendanceEntity>

    @Query("SELECT COUNT(*) FROM attendance WHERE sent_at IS NULL OR selfie_verified_at IS NULL")
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE attendance SET sent_at = :at, last_error = NULL WHERE id = :id")
    suspend fun markSent(id: String, at: Long)

    @Query("UPDATE attendance SET selfie_verified_at = :at, selfie_path = NULL, last_error = NULL, attempt_count = 0 WHERE id = :id")
    suspend fun markSelfieVerified(id: String, at: Long)

    @Query("UPDATE attendance SET attempt_count = attempt_count + 1, next_attempt_at = :next, last_error = :error WHERE id = :id")
    suspend fun fail(id: String, next: Long, error: String?)

    @Query("UPDATE attendance SET next_attempt_at = 0 WHERE sent_at IS NULL OR selfie_verified_at IS NULL")
    suspend fun retryAllNow()
}
