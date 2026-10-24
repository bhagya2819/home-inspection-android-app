// Package api is the Griha central hub (PRD §6.3): accounts, record sync, verified photo and
// report backup. It never processes images or builds PDFs; clients do that on the device.
package api

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Config struct {
	DataDir        string // photo and report objects
	MaxUploadBytes int64
	SessionTTL     time.Duration
	AISecret       []byte // 32 bytes (GRIHA_SECRET_KEY); seals the DeepSeek key. Unset = AI off.
	DeepSeekURL    string // default https://api.deepseek.com
	AIModel        string // default deepseek-flash
	LoginPerMinute int    // sign-in attempts per IP per minute (default 10)
}

type Server struct {
	db      *pgxpool.Pool
	cfg     Config
	log     *slog.Logger
	limiter *loginLimiter
	// loginGate bounds sign-in attempts per IP before any password hashing.
	loginGate *loginGate
	now       func() time.Time
	ai        *http.Client
	lock      lockCache
	lockTTL   time.Duration // how long a read of the developer lock is reused
}

func New(db *pgxpool.Pool, cfg Config, log *slog.Logger) *Server {
	return &Server{db: db, cfg: cfg, log: log, limiter: newLoginLimiter(), loginGate: newLoginGate(cfg.LoginPerMinute), now: time.Now, ai: &http.Client{}, lockTTL: 3 * time.Second} // each AI call sets its own deadline
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("GET /v1/app/status", s.appStatus)

	mux.HandleFunc("POST /v1/auth/login", s.login)
	mux.Handle("POST /v1/auth/logout", s.auth(s.logout))
	mux.Handle("POST /v1/auth/password", s.auth(s.changePassword))
	mux.Handle("GET /v1/me", s.auth(s.me))

	mux.Handle("POST /v1/sync/push", s.auth(s.push))
	mux.Handle("GET /v1/sync/changes", s.auth(s.changes))

	mux.Handle("PUT /v1/photos/{id}/original", s.auth(s.uploadPhoto))
	mux.Handle("GET /v1/photos/{id}/original", s.auth(s.downloadPhoto))
	mux.Handle("PUT /v1/reports/{id}/pdf", s.auth(s.uploadReport))
	mux.Handle("GET /v1/reports/{id}/pdf", s.auth(s.downloadReport))

	mux.Handle("GET /v1/admin/users", s.admin(s.listUsers))
	mux.Handle("POST /v1/admin/users", s.admin(s.createUser))
	mux.Handle("PATCH /v1/admin/users/{id}", s.admin(s.updateUser))

	// M5: admin views, retention settings, storage export/archive, phone sync reports.
	mux.Handle("GET /v1/settings", s.auth(s.getSettings))
	mux.Handle("PATCH /v1/admin/settings", s.admin(s.updateSettings))
	mux.Handle("POST /v1/devices/report", s.auth(s.deviceReport))
	mux.Handle("POST /v1/photos/check", s.auth(s.checkPhotos))
	mux.Handle("GET /v1/admin/inspections", s.admin(s.adminInspections))
	mux.Handle("GET /v1/admin/devices", s.admin(s.adminDevices))
	mux.Handle("PATCH /v1/admin/devices/{id}", s.admin(s.updateDevice))
	mux.Handle("GET /v1/admin/audit", s.admin(s.adminAudit))
	mux.Handle("GET /v1/admin/storage", s.admin(s.storage))
	mux.Handle("POST /v1/admin/exports", s.admin(s.createExport))
	mux.Handle("GET /v1/admin/exports/{id}/zip", s.admin(s.downloadExport))
	mux.Handle("POST /v1/admin/exports/{id}/link", s.admin(s.exportLink))
	mux.Handle("POST /v1/admin/exports/{id}/verified", s.admin(s.exportVerified))
	mux.Handle("POST /v1/admin/exports/{id}/archive", s.admin(s.archiveExport))
	mux.HandleFunc("GET /v1/export-files/{token}", s.downloadExportByLink)

	// AI writing help: the DeepSeek key is write-only; phones only ever talk to us.
	mux.Handle("GET /v1/admin/ai", s.admin(s.adminAI))
	mux.Handle("PUT /v1/admin/ai/key", s.admin(s.setAIKey))
	mux.Handle("DELETE /v1/admin/ai/key", s.admin(s.removeAIKey))
	mux.Handle("POST /v1/ai/finding", s.auth(s.aiFinding))
	mux.Handle("POST /v1/ai/summary", s.auth(s.aiSummary))
	mux.Handle("POST /v1/ai/review", s.auth(s.aiReview))

	// Attendance (feature 1): face check-in/out. Engineers see their own records, admins everyone's.
	mux.Handle("GET /v1/face/model", s.auth(s.faceModel))
	mux.Handle("GET /v1/face/me", s.auth(s.myFace))
	mux.Handle("POST /v1/face/enrollments", s.auth(s.createEnrollment))
	mux.Handle("PUT /v1/face/enrollments/{id}/photo", s.auth(s.uploadEnrollmentPhoto))
	mux.Handle("GET /v1/face/enrollments/{id}/photo", s.auth(s.enrollmentPhoto))
	mux.Handle("GET /v1/admin/face/enrollments", s.admin(s.adminEnrollments))
	mux.Handle("POST /v1/admin/face/enrollments/{id}/decision", s.admin(s.decideEnrollment))
	mux.Handle("POST /v1/attendance", s.auth(s.recordAttendance))
	mux.Handle("PUT /v1/attendance/{id}/selfie", s.auth(s.uploadSelfie))
	mux.Handle("GET /v1/attendance/{id}/selfie", s.auth(s.attendanceSelfie))
	mux.Handle("GET /v1/attendance", s.auth(s.listAttendance))
	mux.Handle("GET /v1/admin/attendance", s.admin(s.adminListAttendance))
	// Live location (feature 2)
	mux.Handle("POST /v1/tracking/batch", s.auth(s.trackingBatch))
	mux.Handle("GET /v1/admin/tracking/team", s.admin(s.trackingTeam))
	mux.Handle("GET /v1/admin/tracking/day", s.admin(s.trackingDay))
	mux.Handle("POST /v1/admin/tracking/live", s.admin(s.trackingLive))
	// Attendance calendar, leave and comp-offs (migration 007)
	mux.Handle("GET /v1/calendar", s.auth(s.myCalendar))
	mux.Handle("GET /v1/admin/calendar", s.admin(s.adminCalendar))
	mux.Handle("GET /v1/admin/calendar/team", s.admin(s.teamCalendar))
	mux.Handle("GET /v1/admin/calendar/export", s.admin(s.attendanceExport))
	mux.Handle("POST /v1/leave", s.auth(s.requestLeave))
	mux.Handle("GET /v1/leave", s.auth(s.myLeave))
	mux.Handle("POST /v1/leave/{id}/cancel", s.auth(s.cancelLeave))
	mux.Handle("GET /v1/admin/leave", s.admin(s.adminLeave))
	mux.Handle("POST /v1/admin/leave", s.admin(s.adminRecordLeave))
	mux.Handle("POST /v1/admin/leave/{id}/decision", s.admin(s.decideLeave))
	mux.Handle("GET /v1/holidays", s.auth(s.listHolidays))
	mux.Handle("PUT /v1/admin/holidays/{day}", s.admin(s.putHoliday))
	mux.Handle("DELETE /v1/admin/holidays/{day}", s.admin(s.deleteHoliday))
	mux.Handle("POST /v1/admin/comp-offs", s.admin(s.setCompOff))
	// Web dashboard (cookie sessions; see web.go)
	mux.HandleFunc("POST /v1/auth/web-login", s.webLogin)
	mux.Handle("GET /v1/reports", s.auth(s.listReports))
	mux.Handle("GET /v1/reports/{id}/docx", s.auth(s.downloadReportDocx))
	mux.Handle("PUT /v1/reports/{id}/content", s.auth(s.putReportContent))
	mux.HandleFunc("GET /web/", s.webStatic)
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, "/web/", http.StatusFound) })
	return s.logRequests(s.lockGate(mux))
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.db.Ping(ctx); err != nil {
		writeError(w, http.StatusServiceUnavailable, "DB_DOWN", "database unreachable")
		return
	}
	free, _ := diskSpace(s.cfg.DataDir)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "data_free_bytes": free})
}

// ---- Migrations ------------------------------------------------------------------------------

//go:embed migrations/*.sql
var migrationFS embed.FS

// Migrate applies embedded migrations in name order, each once, inside its own transaction.
func Migrate(ctx context.Context, db *pgxpool.Pool) error {
	if _, err := db.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		return err
	}
	entries, err := migrationFS.ReadDir("migrations")
	if err != nil {
		return err
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		names = append(names, e.Name())
	}
	sort.Strings(names)
	for _, name := range names {
		err := pgx.BeginFunc(ctx, db, func(tx pgx.Tx) error {
			// Serialises concurrent starts of the server.
			if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(7001)`); err != nil {
				return err
			}
			var done bool
			if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM schema_migrations WHERE name = $1)`, name).Scan(&done); err != nil || done {
				return err
			}
			sql, err := migrationFS.ReadFile("migrations/" + name)
			if err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, string(sql)); err != nil {
				return fmt.Errorf("%s: %w", name, err)
			}
			_, err = tx.Exec(ctx, `INSERT INTO schema_migrations (name) VALUES ($1)`, name)
			return err
		})
		if err != nil {
			return err
		}
	}
	return nil
}

// ---- Helpers -------------------------------------------------------------------------------

// writeLock serialises writes that consume change_seq, so commits happen in sequence order and a
// client paging by cursor can never skip a row that committed late.
func writeLock(ctx context.Context, tx pgx.Tx) error {
	_, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(7002)`)
	return err
}

func (s *Server) auditEvent(ctx context.Context, typ string, userID, deviceID *string, entity, details string) {
	_, err := s.db.Exec(ctx, `INSERT INTO audit_events (type, user_id, device_id, entity, details) VALUES ($1, $2, $3, $4, $5)`,
		typ, userID, deviceID, entity, details)
	if err != nil {
		s.log.Error("audit", "type", typ, "err", err)
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// Errors are {"error": {"code": "...", "message": "..."}}; clients branch on code only.
func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]any{"error": map[string]string{"code": code, "message": message}})
}

func readJSON(w http.ResponseWriter, r *http.Request, limit int64, v any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	dec.UseNumber()
	if err := dec.Decode(v); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeError(w, http.StatusRequestEntityTooLarge, "TOO_LARGE", "request body too large")
		} else {
			writeError(w, http.StatusBadRequest, "BAD_JSON", "invalid JSON body")
		}
		return false
	}
	if _, err := dec.Token(); err != io.EOF {
		writeError(w, http.StatusBadRequest, "BAD_JSON", "unexpected data after JSON body")
		return false
	}
	return true
}

func millis(t time.Time) int64 { return t.UnixMilli() }

func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: 200}
		next.ServeHTTP(rec, r)
		// Never log bodies, tokens or query strings that could carry personal data.
		if path := r.URL.Path; path != "/healthz" {
			if strings.HasPrefix(path, "/v1/export-files/") {
				path = "/v1/export-files/…" // the path is a download token
			}
			s.log.Info("http", "method", r.Method, "path", path, "status", rec.status, "ms", time.Since(start).Milliseconds())
		}
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// ensureDir creates dir and fsyncs its parent so the new entry itself is durable.
func ensureDir(dir string) error {
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return err
	}
	return syncDir(filepath.Dir(dir))
}

func syncDir(dir string) error {
	f, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}

func isUUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i, c := range s {
		switch i {
		case 8, 13, 18, 23:
			if c != '-' {
				return false
			}
		default:
			if !strings.ContainsRune("0123456789abcdefABCDEF", c) {
				return false
			}
		}
	}
	return true
}
