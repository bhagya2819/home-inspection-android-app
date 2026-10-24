package com.homeinspection.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        InspectionEntity::class,
        RoomAreaEntity::class,
        FindingEntity::class,
        PhotoEntity::class,
        SyncOutboxEntity::class,
        ReportEntity::class,
        AuditEventEntity::class,
        UserEntity::class,
        AttendanceEntity::class,
        LocationPointEntity::class,
        TrackingEventEntity::class,
        LeaveRequestEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class HomeInspectionDatabase : RoomDatabase() {
    abstract fun inspectionDao(): InspectionDao
    abstract fun roomAreaDao(): RoomAreaDao
    abstract fun findingDao(): FindingDao
    abstract fun photoDao(): PhotoDao
    abstract fun outboxDao(): SyncOutboxDao
    abstract fun reportDao(): ReportDao
    abstract fun auditDao(): AuditDao
    abstract fun userDao(): UserDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun trackingDao(): TrackingDao
    abstract fun leaveDao(): LeaveDao

    companion object {
        // Deliberately no fallbackToDestructiveMigration(): every schema change ships a real
        // Migration so inspection data survives app updates (PRD §7 schema rule).
        fun build(context: Context): HomeInspectionDatabase =
            Room.databaseBuilder(context, HomeInspectionDatabase::class.java, "home-inspection.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()

        /** M4 sync: revisions on photos/reports, outbox failure details, server users. Additive only. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reports ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN last_error_message TEXT")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN conflict_payload TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS users (id TEXT NOT NULL, employee_code TEXT NOT NULL, " +
                        "display_name TEXT NOT NULL, role TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY(id))"
                )
            }
        }

        /** M5 export/archive and retention: server export/archive markers, audit upload marker. Additive only. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN export_verified_at INTEGER")
                db.execSQL("ALTER TABLE photos ADD COLUMN archived_at INTEGER")
                db.execSQL("ALTER TABLE reports ADD COLUMN export_verified_at INTEGER")
                db.execSQL("ALTER TABLE reports ADD COLUMN archived_at INTEGER")
                db.execSQL("ALTER TABLE audit_events ADD COLUMN uploaded_at INTEGER")
            }
        }

        /** AI help: the report summary paragraph on inspections. Additive only. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inspections ADD COLUMN summary TEXT")
            }
        }

        /** Attendance (face check-in/out). A new table only; nothing existing changes. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `attendance` (`id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`client_at` INTEGER NOT NULL, `time_zone` TEXT NOT NULL, `elapsed_ms` INTEGER NOT NULL, `boot_count` INTEGER, " +
                        "`lat` REAL, `lng` REAL, `accuracy_m` REAL, `location_at` INTEGER, `is_mock` INTEGER NOT NULL, " +
                        "`face_result` TEXT NOT NULL, `face_score` REAL, `enrollment_id` TEXT, `liveness_passed` INTEGER NOT NULL, " +
                        "`selfie_path` TEXT, `selfie_sha256` TEXT NOT NULL, `selfie_size` INTEGER NOT NULL, `sent_at` INTEGER, " +
                        "`selfie_verified_at` INTEGER, `attempt_count` INTEGER NOT NULL, `next_attempt_at` INTEGER NOT NULL, " +
                        "`last_error` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_user_id_client_at` ON `attendance` (`user_id`, `client_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_next_attempt_at` ON `attendance` (`next_attempt_at`)")
            }
        }

        /** Live location: points and sharing events. New tables only; nothing existing changes. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_points` (`id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `client_at` INTEGER NOT NULL, " +
                        "`lat` REAL NOT NULL, `lng` REAL NOT NULL, `accuracy_m` REAL, `battery` INTEGER, `is_mock` INTEGER NOT NULL, " +
                        "`sent_at` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_points_user_id_sent_at` ON `location_points` (`user_id`, `sent_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_points_client_at` ON `location_points` (`client_at`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracking_events` (`id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`source` TEXT, `reason` TEXT, `note` TEXT, `client_at` INTEGER NOT NULL, `sent_at` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracking_events_user_id_sent_at` ON `tracking_events` (`user_id`, `sent_at`)")
            }
        }

        /** Leave requests made on this phone (their upload queue). A new table only; nothing existing changes. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `leave_requests` (`id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`start_day` TEXT NOT NULL, `end_day` TEXT NOT NULL, `half` TEXT, `reason` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`sent_at` INTEGER, `attempt_count` INTEGER NOT NULL, `next_attempt_at` INTEGER NOT NULL, `last_error` TEXT, " +
                        "`failed_at` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_leave_requests_user_id_sent_at` ON `leave_requests` (`user_id`, `sent_at`)")
            }
        }

        /** Common photos per room: findings get a kind. Every existing finding is an ISSUE. Additive only. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE findings ADD COLUMN kind TEXT NOT NULL DEFAULT 'ISSUE'")
            }
        }
    }
}
