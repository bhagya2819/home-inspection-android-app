package api

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// Admin account management (PRD §5.1). The in-app admin screens (M5) call these.

type userRow struct {
	ID           string `json:"id"`
	EmployeeCode string `json:"employee_code"`
	DisplayName  string `json:"display_name"`
	Role         string `json:"role"`
	Status       string `json:"status"`
	MustChange   bool   `json:"must_change_password"`
	CreatedAt    int64  `json:"created_at"`
	LastSeenAt   *int64 `json:"last_seen_at"`
	// Attendance calendar: whether missing check-ins count, and from which day (joining date).
	TrackAttendance bool   `json:"track_attendance"`
	AttendanceFrom  string `json:"attendance_from"`
}

func (s *Server) listUsers(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `
		SELECT u.id::text, u.employee_code, u.display_name, u.role, u.status, u.must_change_password,
		       (extract(epoch FROM u.created_at) * 1000)::bigint,
		       (SELECT (extract(epoch FROM max(d.last_seen_at)) * 1000)::bigint FROM devices d WHERE d.user_id = u.id),
		       u.track_attendance, u.attendance_from::text
		FROM users u ORDER BY u.display_name`)
	if err != nil {
		s.internal(w, "users", err)
		return
	}
	defer rows.Close()
	out := []userRow{}
	for rows.Next() {
		var u userRow
		if err := rows.Scan(&u.ID, &u.EmployeeCode, &u.DisplayName, &u.Role, &u.Status, &u.MustChange, &u.CreatedAt, &u.LastSeenAt,
			&u.TrackAttendance, &u.AttendanceFrom); err != nil {
			s.internal(w, "users", err)
			return
		}
		out = append(out, u)
	}
	writeJSON(w, http.StatusOK, map[string]any{"users": out})
}

func (s *Server) createUser(w http.ResponseWriter, r *http.Request) {
	var req struct {
		EmployeeCode string `json:"employee_code"`
		DisplayName  string `json:"display_name"`
		Role         string `json:"role"`
		Password     string `json:"password"`
	}
	if !readJSON(w, r, 4<<10, &req) {
		return
	}
	id, err := CreateUser(r.Context(), s.db, req.EmployeeCode, req.DisplayName, req.Role, req.Password, true)
	switch {
	case errors.Is(err, ErrInvalidUser):
		writeError(w, http.StatusBadRequest, "INVALID", err.Error())
	case errors.Is(err, ErrDuplicateCode):
		writeError(w, http.StatusConflict, "DUPLICATE", "employee code already exists")
	case err != nil:
		s.internal(w, "create user", err)
	default:
		admin := userFrom(r)
		s.auditEvent(r.Context(), "USER_CREATED", &admin.ID, admin.deviceID, "USER:"+id, "role="+req.Role)
		writeJSON(w, http.StatusCreated, map[string]any{"id": id})
	}
}

// updateUser changes name/role, disables/enables, or resets the password. Disabling or a reset
// revokes the user's sessions, so their devices stop syncing at next contact.
func (s *Server) updateUser(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var req struct {
		DisplayName *string `json:"display_name"`
		Role        *string `json:"role"`
		Status      *string `json:"status"`
		Password    *string `json:"password"`
		// Attendance calendar (see calendar.go).
		TrackAttendance *bool   `json:"track_attendance"`
		AttendanceFrom  *string `json:"attendance_from"` // YYYY-MM-DD
	}
	if !readJSON(w, r, 4<<10, &req) || !isUUID(id) {
		if !isUUID(id) {
			writeError(w, http.StatusBadRequest, "INVALID", "id must be a UUID")
		}
		return
	}
	admin := userFrom(r)
	if id == admin.ID && ((req.Status != nil && *req.Status != "ACTIVE") || (req.Role != nil && *req.Role != "ADMIN")) {
		writeError(w, http.StatusBadRequest, "SELF_LOCKOUT", "you cannot disable or demote your own account")
		return
	}
	var hash *string
	if req.Password != nil {
		if !validPassword(*req.Password) {
			writeError(w, http.StatusBadRequest, "WEAK_PASSWORD", fmt.Sprintf("use at least %d characters", minPasswordLen))
			return
		}
		h, err := HashPassword(*req.Password)
		if err != nil {
			s.internal(w, "update user", err)
			return
		}
		hash = &h
	}
	if req.Role != nil && *req.Role != "ADMIN" && *req.Role != "INSPECTOR" {
		writeError(w, http.StatusBadRequest, "INVALID", "role must be ADMIN or INSPECTOR")
		return
	}
	if req.Status != nil && *req.Status != "ACTIVE" && *req.Status != "DISABLED" {
		writeError(w, http.StatusBadRequest, "INVALID", "status must be ACTIVE or DISABLED")
		return
	}
	var from *time.Time
	if req.AttendanceFrom != nil {
		d, ok := parseDay(*req.AttendanceFrom)
		if !ok || d.Year() < 2020 || d.Year() > 2100 {
			writeError(w, http.StatusBadRequest, "INVALID", "attendance_from must be YYYY-MM-DD")
			return
		}
		from = &d
	}
	if req.DisplayName != nil && strings.TrimSpace(*req.DisplayName) == "" {
		writeError(w, http.StatusBadRequest, "INVALID", "display_name cannot be empty")
		return
	}
	var found bool
	err := pgx.BeginFunc(r.Context(), s.db, func(tx pgx.Tx) error {
		if err := writeLock(r.Context(), tx); err != nil {
			return err
		}
		tag, err := tx.Exec(r.Context(), `
			UPDATE users SET
			  display_name = COALESCE($2, display_name),
			  role = COALESCE($3, role),
			  status = COALESCE($4, status),
			  disabled_at = CASE WHEN $4 = 'DISABLED' THEN now() WHEN $4 = 'ACTIVE' THEN NULL ELSE disabled_at END,
			  password_hash = COALESCE($5, password_hash),
			  must_change_password = CASE WHEN $5 IS NULL THEN must_change_password ELSE true END,
			  track_attendance = COALESCE($6, track_attendance),
			  attendance_from = COALESCE($7::date, attendance_from),
			  updated_at = now(), change_seq = nextval('change_seq')
			WHERE id = $1`, id, trimPtr(req.DisplayName), req.Role, req.Status, hash, req.TrackAttendance, from)
		if err != nil {
			return err
		}
		found = tag.RowsAffected() == 1
		if found && (hash != nil || (req.Status != nil && *req.Status == "DISABLED")) {
			_, err = tx.Exec(r.Context(), `UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`, id)
		}
		return err
	})
	if err != nil {
		s.internal(w, "update user", err)
		return
	}
	if !found {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such user")
		return
	}
	details := []string{}
	if req.Status != nil {
		details = append(details, "status="+*req.Status)
	}
	if req.Role != nil {
		details = append(details, "role="+*req.Role)
	}
	if hash != nil {
		details = append(details, "password_reset")
	}
	if req.TrackAttendance != nil {
		details = append(details, fmt.Sprintf("track_attendance=%t", *req.TrackAttendance))
	}
	if from != nil {
		details = append(details, "attendance_from="+from.Format(dayLayout))
	}
	s.auditEvent(r.Context(), "USER_UPDATED", &admin.ID, admin.deviceID, "USER:"+id, strings.Join(details, " "))
	w.WriteHeader(http.StatusNoContent)
}

func trimPtr(p *string) *string {
	if p == nil {
		return nil
	}
	t := strings.TrimSpace(*p)
	return &t
}

var (
	ErrInvalidUser   = errors.New("employee_code, display_name, role (ADMIN|INSPECTOR) and a password of 8+ characters are required")
	ErrDuplicateCode = errors.New("employee code already exists")
)

// CreateUser is shared by the admin API and the `create-user` bootstrap command.
func CreateUser(ctx context.Context, db interface {
	Begin(context.Context) (pgx.Tx, error)
}, code, name, role, password string, mustChange bool) (string, error) {
	code = strings.ToUpper(strings.TrimSpace(code))
	name = strings.TrimSpace(name)
	if code == "" || len(code) > 40 || name == "" || len(name) > 100 || (role != "ADMIN" && role != "INSPECTOR") || !validPassword(password) {
		return "", ErrInvalidUser
	}
	hash, err := HashPassword(password)
	if err != nil {
		return "", err
	}
	id := newUUID()
	tx, err := db.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx)
	if err := writeLock(ctx, tx); err != nil {
		return "", err
	}
	// Inspectors are on the attendance calendar from today; admins only when the admin switches them on.
	_, err = tx.Exec(ctx, `INSERT INTO users (id, employee_code, display_name, role, password_hash, must_change_password, track_attendance)
		VALUES ($1, $2, $3, $4, $5, $6, $4 = 'INSPECTOR')`,
		id, code, name, role, hash, mustChange)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23505" {
		return "", ErrDuplicateCode
	}
	if err != nil {
		return "", err
	}
	return id, tx.Commit(ctx)
}

func newUUID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	const hexdigits = "0123456789abcdef"
	out := make([]byte, 0, 36)
	for i, x := range b {
		if i == 4 || i == 6 || i == 8 || i == 10 {
			out = append(out, '-')
		}
		out = append(out, hexdigits[x>>4], hexdigits[x&0x0f])
	}
	return string(out)
}
