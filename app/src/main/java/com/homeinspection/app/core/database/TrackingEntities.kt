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
 * Live location (feature 2, DB v6). Points and sharing events are append-only and are their own
 * upload queue ([sentAt]), like attendance rows: no sync_outbox, no revisions. Sent points are
 * removed from the phone after a week; the server keeps them 90 days.
 */
@Entity(tableName = "location_points", indices = [Index(value = ["user_id", "sent_at"]), Index("client_at")])
data class LocationPointEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "client_at") val clientAt: Long,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float?,
    /** Battery percent at the fix, null = unknown. */
    val battery: Int?,
    @ColumnInfo(name = "is_mock") val isMock: Boolean,
    @ColumnInfo(name = "sent_at") val sentAt: Long? = null,
)

@Entity(tableName = "tracking_events", indices = [Index(value = ["user_id", "sent_at"])])
data class TrackingEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    /** START, STOP, PAUSE, INTERRUPTED, LOCATION_OFF, LOCATION_ON, MOCK, CONSENT (see server/API.md). */
    val kind: String,
    val source: String? = null,
    val reason: String? = null,
    val note: String? = null,
    @ColumnInfo(name = "client_at") val clientAt: Long,
    @ColumnInfo(name = "sent_at") val sentAt: Long? = null,
)

@Dao
interface TrackingDao {
    @Insert
    suspend fun insertPoint(row: LocationPointEntity)

    @Insert
    suspend fun insertEvent(row: TrackingEventEntity)

    /** [userId]'s points the server doesn't have yet; another person's wait for them to sign in. */
    @Query("SELECT * FROM location_points WHERE user_id = :userId AND sent_at IS NULL ORDER BY client_at LIMIT :limit")
    suspend fun unsentPoints(userId: String, limit: Int): List<LocationPointEntity>

    @Query("SELECT * FROM tracking_events WHERE user_id = :userId AND sent_at IS NULL ORDER BY client_at LIMIT :limit")
    suspend fun unsentEvents(userId: String, limit: Int): List<TrackingEventEntity>

    @Query("UPDATE location_points SET sent_at = :at WHERE id IN (:ids)")
    suspend fun markPointsSent(ids: List<String>, at: Long)

    @Query("UPDATE tracking_events SET sent_at = :at WHERE id IN (:ids)")
    suspend fun markEventsSent(ids: List<String>, at: Long)

    /** Unsent events only: a new event (turned off, location off…) should reach the admin soon. */
    @Query("SELECT COUNT(*) FROM tracking_events WHERE sent_at IS NULL")
    fun observeUnsentEvents(): Flow<Int>

    @Query("SELECT COUNT(*) FROM location_points WHERE sent_at IS NULL")
    fun observeUnsentPoints(): Flow<Int>

    @Query("SELECT * FROM location_points WHERE user_id = :userId ORDER BY client_at DESC LIMIT 1")
    fun observeLatestPoint(userId: String): Flow<LocationPointEntity?>

    @Query("DELETE FROM location_points WHERE sent_at IS NOT NULL AND sent_at < :before")
    suspend fun deleteSentPointsBefore(before: Long): Int

    @Query("DELETE FROM tracking_events WHERE sent_at IS NOT NULL AND sent_at < :before")
    suspend fun deleteSentEventsBefore(before: Long): Int
}
