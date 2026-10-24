package com.homeinspection.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Data written by older builds must survive every upgrade unchanged (PRD §7 schema rule). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HomeInspectionDatabase::class.java)

    @Test
    fun migrate1To2KeepsData() {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO inspections (id, client_name, client_phone, property_address, inspector_user_id, inspection_date, " +
                    "time_zone, status, report_version, created_at, updated_at, revision) VALUES " +
                    "('i1', 'Priya', '', 'Guntur', 'local-inspector', '2026-10-20', 'Asia/Kolkata', 'FINALIZED', 1, 1, 2, 0)"
            )
            execSQL("INSERT INTO rooms (id, inspection_id, name, sort_order, is_custom, created_at, updated_at, revision) VALUES ('r1', 'i1', 'Kitchen', 0, 0, 1, 1, 0)")
            execSQL(
                "INSERT INTO findings (id, inspection_id, room_id, rough_note, status, sort_order, created_by, created_at, updated_at, revision) " +
                    "VALUES ('f1', 'i1', 'r1', 'crack', 'OPEN', 0, 'local-inspector', 1, 1, 0)"
            )
            execSQL(
                "INSERT INTO photos (id, inspection_id, room_id, finding_id, local_original_path, mime_type, width, height, " +
                    "file_size_bytes, sha256, captured_at, upload_state, created_at, updated_at) VALUES " +
                    "('p1', 'i1', 'r1', 'f1', 'photos/originals/i1/p1.jpg', 'image/jpeg', 4000, 3000, 123, 'abc', 1, 'QUEUED', 1, 1)"
            )
            execSQL(
                "INSERT INTO reports (id, inspection_id, version, local_pdf_path, content_hash, generated_at) " +
                    "VALUES ('rep1', 'i1', 1, 'reports/i1/v1.pdf', 'h', 5)"
            )
            execSQL(
                "INSERT INTO sync_outbox (entity_type, entity_id, operation, idempotency_key, payload_version, attempt_count, " +
                    "next_attempt_at, created_at, updated_at) VALUES ('PHOTO', 'p1', 'UPLOAD_ORIGINAL', 'k1', 1, 0, 0, 0, 0)"
            )
            close()
        }
        // Runs the migration and validates the result against the v2 schema Room expects.
        helper.runMigrationsAndValidate(DB, 2, true, HomeInspectionDatabase.MIGRATION_1_2).apply {
            query("SELECT sha256, local_original_path, revision FROM photos WHERE id = 'p1'").use {
                it.moveToFirst()
                assertEquals("abc", it.getString(0))
                assertEquals("photos/originals/i1/p1.jpg", it.getString(1))
                assertEquals(0L, it.getLong(2))
            }
            query("SELECT status, report_version FROM inspections WHERE id = 'i1'").use {
                it.moveToFirst(); assertEquals("FINALIZED", it.getString(0)); assertEquals(1, it.getInt(1))
            }
            query("SELECT revision FROM reports WHERE id = 'rep1'").use { it.moveToFirst(); assertEquals(0L, it.getLong(0)) }
            query("SELECT operation, last_error_message, conflict_payload FROM sync_outbox").use {
                it.moveToFirst(); assertEquals("UPLOAD_ORIGINAL", it.getString(0)); assert(it.isNull(1) && it.isNull(2))
            }
            query("SELECT COUNT(*) FROM users").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            close()
        }
    }

    /** A v2 (M4) database keeps every row, and the M5 columns start empty (nothing exported or removed). */
    @Test
    fun migrate2To3KeepsData() {
        helper.createDatabase(DB, 2).apply {
            execSQL(
                "INSERT INTO inspections (id, client_name, client_phone, property_address, inspector_user_id, inspection_date, " +
                    "time_zone, status, report_version, created_at, updated_at, revision) VALUES " +
                    "('i1', 'Priya', '', 'Guntur', 'u1', '2026-10-20', 'Asia/Kolkata', 'FINALIZED', 1, 1, 2, 3)"
            )
            execSQL("INSERT INTO rooms (id, inspection_id, name, sort_order, is_custom, created_at, updated_at, revision) VALUES ('r1', 'i1', 'Kitchen', 0, 0, 1, 1, 1)")
            execSQL(
                "INSERT INTO findings (id, inspection_id, room_id, rough_note, status, sort_order, created_by, created_at, updated_at, revision) " +
                    "VALUES ('f1', 'i1', 'r1', 'crack', 'OPEN', 0, 'u1', 1, 1, 1)"
            )
            execSQL(
                "INSERT INTO photos (id, inspection_id, room_id, finding_id, local_original_path, mime_type, width, height, " +
                    "file_size_bytes, sha256, captured_at, upload_state, backup_verified_at, backup_checksum, created_at, updated_at, revision) " +
                    "VALUES ('p1', 'i1', 'r1', 'f1', 'photos/originals/i1/p1.jpg', 'image/jpeg', 4000, 3000, 123, 'abc', 1, " +
                    "'SERVER_VERIFIED', 9, 'abc', 1, 1, 1)"
            )
            execSQL(
                "INSERT INTO reports (id, inspection_id, version, local_pdf_path, content_hash, generated_at, backup_verified_at, revision) " +
                    "VALUES ('rep1', 'i1', 1, 'reports/i1/v1.pdf', 'h', 5, 6, 1)"
            )
            execSQL("INSERT INTO audit_events (id, type, created_at) VALUES ('a1', 'REPORT_FINALIZED', 7)")
            execSQL("INSERT INTO users (id, employee_code, display_name, role, status) VALUES ('u1', 'DEVA', 'Deva', 'ADMIN', 'ACTIVE')")
            close()
        }
        helper.runMigrationsAndValidate(DB, 3, true, HomeInspectionDatabase.MIGRATION_2_3).apply {
            query("SELECT upload_state, backup_verified_at, backup_checksum, export_verified_at, archived_at, original_deleted_at FROM photos WHERE id = 'p1'").use {
                it.moveToFirst()
                assertEquals("SERVER_VERIFIED", it.getString(0))
                assertEquals(9L, it.getLong(1))
                assertEquals("abc", it.getString(2))
                assert(it.isNull(3) && it.isNull(4) && it.isNull(5))
            }
            query("SELECT backup_verified_at, export_verified_at, archived_at FROM reports WHERE id = 'rep1'").use {
                it.moveToFirst(); assertEquals(6L, it.getLong(0)); assert(it.isNull(1) && it.isNull(2))
            }
            query("SELECT type, uploaded_at FROM audit_events WHERE id = 'a1'").use {
                it.moveToFirst(); assertEquals("REPORT_FINALIZED", it.getString(0)); assert(it.isNull(1))
            }
            query("SELECT COUNT(*) FROM users").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            close()
        }
    }

    /** A v3 (0.5.x) database keeps every row; inspections start without a summary. */
    @Test
    fun migrate3To4KeepsData() {
        helper.createDatabase(DB, 3).apply {
            execSQL(
                "INSERT INTO inspections (id, client_name, client_phone, property_address, inspector_user_id, inspection_date, " +
                    "time_zone, status, report_version, created_at, updated_at, revision) VALUES " +
                    "('i1', 'Priya', '98', 'Guntur', 'u1', '2026-10-20', 'Asia/Kolkata', 'FINALIZED', 2, 1, 2, 5)"
            )
            execSQL("INSERT INTO rooms (id, inspection_id, name, sort_order, is_custom, created_at, updated_at, revision) VALUES ('r1', 'i1', 'Kitchen', 0, 0, 1, 1, 1)")
            execSQL(
                "INSERT INTO findings (id, inspection_id, room_id, rough_note, detailed_observation, status, sort_order, created_by, " +
                    "created_at, updated_at, revision) VALUES ('f1', 'i1', 'r1', 'crack', 'Hairline crack', 'OPEN', 0, 'u1', 1, 1, 1)"
            )
            close()
        }
        helper.runMigrationsAndValidate(DB, 4, true, HomeInspectionDatabase.MIGRATION_3_4).apply {
            query("SELECT client_name, status, report_version, revision, summary FROM inspections WHERE id = 'i1'").use {
                it.moveToFirst()
                assertEquals("Priya", it.getString(0))
                assertEquals("FINALIZED", it.getString(1))
                assertEquals(2, it.getInt(2))
                assertEquals(5L, it.getLong(3))
                assertTrue(it.isNull(4)) // Kotlin assert() is disabled on Android
            }
            query("SELECT detailed_observation FROM findings WHERE id = 'f1'").use { it.moveToFirst(); assertEquals("Hairline crack", it.getString(0)) }
            close()
        }
    }

    /** A v4 (0.7.x) database keeps every row and gains an empty attendance table that accepts a check-in. */
    @Test
    fun migrate4To5AddsAttendance() {
        helper.createDatabase(DB, 4).apply {
            execSQL(
                "INSERT INTO inspections (id, client_name, client_phone, property_address, inspector_user_id, inspection_date, " +
                    "time_zone, status, report_version, created_at, updated_at, revision, summary) VALUES " +
                    "('i1', 'Priya', '98', 'Guntur', 'u1', '2026-10-21', 'Asia/Kolkata', 'IN_PROGRESS', 0, 1, 2, 3, 'Fair')"
            )
            execSQL(
                "INSERT INTO sync_outbox (entity_type, entity_id, operation, idempotency_key, payload_version, attempt_count, " +
                    "next_attempt_at, created_at, updated_at) VALUES ('INSPECTION', 'i1', 'UPSERT', 'k1', 1, 0, 0, 0, 0)"
            )
            close()
        }
        helper.runMigrationsAndValidate(DB, 5, true, HomeInspectionDatabase.MIGRATION_4_5).apply {
            query("SELECT client_name, revision, summary FROM inspections WHERE id = 'i1'").use {
                it.moveToFirst()
                assertEquals("Priya", it.getString(0)); assertEquals(3L, it.getLong(1)); assertEquals("Fair", it.getString(2))
            }
            query("SELECT COUNT(*) FROM sync_outbox").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            query("SELECT COUNT(*) FROM attendance").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            execSQL(
                "INSERT INTO attendance (id, user_id, kind, client_at, time_zone, elapsed_ms, is_mock, face_result, liveness_passed, " +
                    "selfie_path, selfie_sha256, selfie_size, attempt_count, next_attempt_at, created_at) VALUES " +
                    "('a1', 'u1', 'IN', 10, 'Asia/Kolkata', 5, 0, 'MATCH', 1, 'attendance/a1.jpg', 'h', 9, 0, 0, 10)"
            )
            query("SELECT kind, lat, sent_at FROM attendance WHERE id = 'a1'").use {
                it.moveToFirst(); assertEquals("IN", it.getString(0)); assertTrue(it.isNull(1) && it.isNull(2))
            }
            close()
        }
    }

    @Test
    fun migrate5To6AddsTracking() {
        helper.createDatabase(DB, 5).apply {
            execSQL(
                "INSERT INTO attendance (id, user_id, kind, client_at, time_zone, elapsed_ms, is_mock, face_result, liveness_passed, " +
                    "selfie_path, selfie_sha256, selfie_size, attempt_count, next_attempt_at, created_at) VALUES " +
                    "('a1', 'u1', 'IN', 10, 'Asia/Kolkata', 5, 0, 'MATCH', 1, 'attendance/a1.jpg', 'h', 9, 0, 0, 10)"
            )
            close()
        }
        helper.runMigrationsAndValidate(DB, 6, true, HomeInspectionDatabase.MIGRATION_5_6).apply {
            query("SELECT kind, selfie_path FROM attendance WHERE id = 'a1'").use {
                it.moveToFirst(); assertEquals("IN", it.getString(0)); assertEquals("attendance/a1.jpg", it.getString(1))
            }
            execSQL("INSERT INTO location_points (id, user_id, client_at, lat, lng, accuracy_m, battery, is_mock) VALUES ('p1', 'u1', 20, 17.4, 78.4, 12.5, 80, 0)")
            execSQL("INSERT INTO tracking_events (id, user_id, kind, source, client_at) VALUES ('e1', 'u1', 'START', 'CHECK_IN', 10)")
            query("SELECT lat, battery, sent_at FROM location_points WHERE id = 'p1'").use {
                it.moveToFirst(); assertEquals(17.4, it.getDouble(0), 1e-9); assertEquals(80, it.getInt(1)); assertTrue(it.isNull(2))
            }
            query("SELECT source, reason FROM tracking_events WHERE id = 'e1'").use {
                it.moveToFirst(); assertEquals("CHECK_IN", it.getString(0)); assertTrue(it.isNull(1))
            }
            close()
        }
    }

    @Test
    fun migrate6To7AddsLeaveQueue() {
        helper.createDatabase(DB, 6).apply {
            execSQL("INSERT INTO location_points (id, user_id, client_at, lat, lng, is_mock) VALUES ('p1', 'u1', 20, 17.4, 78.4, 0)")
            close()
        }
        helper.runMigrationsAndValidate(DB, 7, true, HomeInspectionDatabase.MIGRATION_6_7).apply {
            query("SELECT lat FROM location_points WHERE id = 'p1'").use { it.moveToFirst(); assertEquals(17.4, it.getDouble(0), 1e-9) }
            execSQL(
                "INSERT INTO leave_requests (id, user_id, kind, start_day, end_day, half, reason, created_at, attempt_count, next_attempt_at) " +
                    "VALUES ('l1', 'u1', 'SICK', '2026-10-22', '2026-10-22', 'FIRST', 'fever', 5, 0, 0)"
            )
            query("SELECT kind, half, sent_at, failed_at FROM leave_requests WHERE id = 'l1'").use {
                it.moveToFirst(); assertEquals("SICK", it.getString(0)); assertEquals("FIRST", it.getString(1))
                assertTrue(it.isNull(2) && it.isNull(3))
            }
            close()
        }
    }

    @Test
    fun migrate7To8MakesExistingFindingsIssues() {
        helper.createDatabase(DB, 7).apply {
            execSQL(
                "INSERT INTO inspections (id, client_name, client_phone, property_address, inspector_user_id, inspection_date, " +
                    "time_zone, status, report_version, created_at, updated_at, revision) VALUES " +
                    "('i1', 'Priya', '', 'Guntur', 'u1', '2026-10-21', 'Asia/Kolkata', 'IN_PROGRESS', 0, 1, 2, 3)"
            )
            execSQL("INSERT INTO rooms (id, inspection_id, name, sort_order, is_custom, created_at, updated_at, revision) VALUES ('r1', 'i1', 'Kitchen', 0, 0, 1, 1, 0)")
            execSQL(
                "INSERT INTO findings (id, inspection_id, room_id, title, rough_note, status, sort_order, created_by, created_at, updated_at, revision) " +
                    "VALUES ('f1', 'i1', 'r1', 'Crack', 'crack', 'OPEN', 0, 'u1', 1, 1, 4)"
            )
            close()
        }
        helper.runMigrationsAndValidate(DB, 8, true, HomeInspectionDatabase.MIGRATION_7_8).apply {
            query("SELECT kind, title, revision FROM findings WHERE id = 'f1'").use {
                it.moveToFirst(); assertEquals("ISSUE", it.getString(0)); assertEquals("Crack", it.getString(1)); assertEquals(4, it.getLong(2))
            }
            execSQL(
                "INSERT INTO findings (id, inspection_id, room_id, rough_note, status, sort_order, created_by, created_at, updated_at, revision, kind) " +
                    "VALUES ('f2', 'i1', 'r1', '', 'OPEN', 1, 'u1', 5, 5, 0, 'ROOM_PHOTOS')"
            )
            query("SELECT COUNT(*) FROM findings WHERE kind = 'ISSUE'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            close()
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
