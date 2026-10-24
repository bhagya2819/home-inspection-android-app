package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
)

// Attendance (feature 1). Phones check the face themselves (OpenCV YuNet + SFace, offline); the
// server keeps each engineer's approved face embeddings, the check-in/out records and their
// selfies. An engineer sees only their own records; admins see everyone's. Selfies are deleted
// after selfieRetention, the records stay.

const (
	// The compact (int8) SFace: 9.9 MB instead of 38.7 MB (a ~1 min download from India) for
	// 99.32 % instead of 99.40 % on LFW (user decision 2026-10-21). Embeddings only compare within
	// one model, so enrolments made with any other model are ignored (the engineer sets up again).
	faceModelName   = "sface-2021dec-int8"
	faceModelFile   = "face_recognition_sface_2021dec_int8.onnx" // under DATA_DIR/models/, placed by the developer
	embeddingDims   = 128
	maxEnrollShots  = 10
	selfieRetention = 90 * 24 * time.Hour
	// An upload's elapsed-since-boot is trusted for the server's time estimate only this long:
	// beyond it, phone sleep accounting makes the estimate too loose to flag anything.
	maxEstimateAge = 30 * 24 * time.Hour
)

var faceResults = map[string]bool{"MATCH": true, "WEAK": true, "NO_MATCH": true, "NOT_ENROLLED": true, "UNAVAILABLE": true}

// ---- Face model ----------------------------------------------------------------------------

// faceModel serves the recognition model to phones (Range-resumable). The phone pins its SHA-256,
// so a swapped file is refused there; the header is for logging and admin diagnostics.
func (s *Server) faceModel(w http.ResponseWriter, r *http.Request) {
	path := filepath.Join(s.cfg.DataDir, "models", faceModelFile)
	f, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		writeError(w, http.StatusNotFound, "MODEL_MISSING", "the face model is not installed on the server")
		return
	}
	if err != nil {
		s.internal(w, "face model", err)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		s.internal(w, "face model", err)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("X-Face-Model", faceModelName)
	http.ServeContent(w, r, "", st.ModTime(), f)
}

// ---- Enrolment -----------------------------------------------------------------------------

func validEmbeddings(e [][]float32, max int) bool {
	if len(e) == 0 || len(e) > max {
		return false
	}
	for _, v := range e {
		if len(v) != embeddingDims {
			return false
		}
		for _, x := range v {
			if math.IsNaN(float64(x)) || math.IsInf(float64(x), 0) {
				return false
			}
		}
	}
	return true
}

// createEnrollment stores a new PENDING enrolment for the caller, replacing an older pending one.
// The approved one (if any) keeps working until the admin approves the new one.
func (s *Server) createEnrollment(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	var req struct {
		ID          string      `json:"id"`
		Model       string      `json:"model"`
		Embeddings  [][]float32 `json:"embeddings"`
		PhotoSHA256 string      `json:"photo_sha256"`
		PhotoSize   int64       `json:"photo_size"`
	}
	if !readJSON(w, r, 256<<10, &req) {
		return
	}
	if !isUUID(req.ID) || req.Model != faceModelName || !validEmbeddings(req.Embeddings, maxEnrollShots) ||
		len(req.PhotoSHA256) != 64 || req.PhotoSize <= 0 || req.PhotoSize > s.cfg.MaxUploadBytes {
		writeError(w, http.StatusBadRequest, "INVALID", "invalid enrolment")
		return
	}
	emb, _ := json.Marshal(req.Embeddings)
	var created bool
	err := pgx.BeginFunc(r.Context(), s.db, func(tx pgx.Tx) error {
		var owner string
		err := tx.QueryRow(r.Context(), `SELECT user_id::text FROM face_enrollments WHERE id = $1`, req.ID).Scan(&owner)
		if err == nil {
			if owner != u.ID {
				return errForbidden
			}
			return nil // retry of a POST whose response was lost
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		if _, err := tx.Exec(r.Context(), `UPDATE face_enrollments SET status = 'REPLACED' WHERE user_id = $1 AND status = 'PENDING'`, u.ID); err != nil {
			return err
		}
		created = true
		_, err = tx.Exec(r.Context(), `INSERT INTO face_enrollments (id, user_id, device_id, model, embeddings, status, photo_sha256, photo_size)
			VALUES ($1, $2, $3, $4, $5, 'PENDING', lower($6), $7)`, req.ID, u.ID, u.deviceID, req.Model, emb, req.PhotoSHA256, req.PhotoSize)
		return err
	})
	if errors.Is(err, errForbidden) {
		writeError(w, http.StatusForbidden, "FORBIDDEN", "not your enrolment")
		return
	}
	if err != nil {
		s.internal(w, "create enrollment", err)
		return
	}
	if created {
		s.auditEvent(r.Context(), "FACE_ENROLLED", &u.ID, u.deviceID, "FACE:"+req.ID, fmt.Sprintf("shots=%d", len(req.Embeddings)))
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": req.ID, "status": "PENDING"})
}

var errForbidden = errors.New("forbidden")

func (s *Server) uploadEnrollmentPhoto(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	id := r.PathValue("id")
	if !isUUID(id) {
		writeError(w, http.StatusBadRequest, "INVALID", "id must be a UUID")
		return
	}
	var owner, wantHash string
	var wantSize int64
	var key *string
	var verifiedAt *time.Time
	err := s.db.QueryRow(r.Context(), `SELECT user_id::text, photo_sha256, photo_size, photo_key, photo_verified_at FROM face_enrollments WHERE id = $1`, id).
		Scan(&owner, &wantHash, &wantSize, &key, &verifiedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		drain(r, s.cfg.MaxUploadBytes)
		writeError(w, http.StatusConflict, "METADATA_MISSING", "create the enrolment before uploading its photo")
		return
	}
	if err != nil {
		s.internal(w, "enrollment photo", err)
		return
	}
	if owner != u.ID {
		drain(r, s.cfg.MaxUploadBytes)
		writeError(w, http.StatusForbidden, "FORBIDDEN", "not your enrolment")
		return
	}
	s.receiveObject(w, r, id, wantHash, wantSize, key, verifiedAt, filepath.Join("faces", owner, id+".jpg"), "FACE:"+id,
		`UPDATE face_enrollments SET photo_key = $2, photo_verified_at = $3 WHERE id = $1`)
}

// receiveObject is the shared tail of the attendance/face uploads: idempotent replay, size checks,
// verified store, then one UPDATE (args: id, key, verified_at).
func (s *Server) receiveObject(w http.ResponseWriter, r *http.Request, id, wantHash string, wantSize int64,
	key *string, verifiedAt *time.Time, newKey, auditEntity, update string) {
	if key != nil && verifiedAt != nil {
		drain(r, s.cfg.MaxUploadBytes)
		writeJSON(w, http.StatusOK, verifiedBody(*key, wantHash, wantSize, *verifiedAt))
		return
	}
	if r.ContentLength >= 0 && r.ContentLength != wantSize {
		drain(r, s.cfg.MaxUploadBytes)
		writeError(w, http.StatusBadRequest, "SIZE_MISMATCH", fmt.Sprintf("expected %d bytes", wantSize))
		return
	}
	if wantSize > s.cfg.MaxUploadBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "TOO_LARGE", "file too large")
		return
	}
	n, got, ok := s.storeVerified(w, r, id, wantSize, wantHash, newKey, auditEntity)
	if !ok {
		return
	}
	now := s.now()
	if _, err := s.db.Exec(r.Context(), update, id, newKey, now); err != nil {
		s.internal(w, "upload", err)
		return
	}
	writeJSON(w, http.StatusOK, verifiedBody(newKey, got, n, now))
}

type enrollmentView struct {
	ID         string      `json:"id"`
	UserID     string      `json:"user_id"`
	Status     string      `json:"status"`
	Model      string      `json:"model"`
	Embeddings [][]float32 `json:"embeddings,omitempty"`
	Shots      int         `json:"shots"`
	HasPhoto   bool        `json:"has_photo"`
	CreatedAt  int64       `json:"created_at"`
	DecidedAt  *int64      `json:"decided_at,omitempty"`
}

// myFace answers what a phone needs to check this engineer's face offline: the approved
// embeddings (if any) and whether a newer enrolment is waiting for the admin.
func (s *Server) myFace(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	rows, err := s.db.Query(r.Context(), `SELECT id::text, user_id::text, status, model, embeddings, photo_verified_at IS NOT NULL, created_at, decided_at
		FROM face_enrollments WHERE user_id = $1 AND model = $2 AND status IN ('APPROVED', 'PENDING', 'REJECTED') ORDER BY created_at DESC LIMIT 5`,
		u.ID, faceModelName)
	if err != nil {
		s.internal(w, "my face", err)
		return
	}
	list, err := scanEnrollments(rows, true)
	if err != nil {
		s.internal(w, "my face", err)
		return
	}
	out := map[string]any{"model": faceModelName, "approved": nil, "pending": nil, "rejected": nil}
	for _, e := range list {
		key := map[string]string{"APPROVED": "approved", "PENDING": "pending", "REJECTED": "rejected"}[e.Status]
		if out[key] == nil {
			if e.Status != "APPROVED" {
				e.Embeddings = nil // only the approved embeddings are used for matching
			}
			out[key] = e
		}
	}
	writeJSON(w, http.StatusOK, out)
}

func scanEnrollments(rows pgx.Rows, withEmbeddings bool) ([]enrollmentView, error) {
	defer rows.Close()
	list := []enrollmentView{}
	for rows.Next() {
		var e enrollmentView
		var raw []byte
		var created time.Time
		var decided *time.Time
		if err := rows.Scan(&e.ID, &e.UserID, &e.Status, &e.Model, &raw, &e.HasPhoto, &created, &decided); err != nil {
			return nil, err
		}
		var emb [][]float32
		if err := json.Unmarshal(raw, &emb); err != nil {
			return nil, err
		}
		e.Shots = len(emb)
		if withEmbeddings {
			e.Embeddings = emb
		}
		e.CreatedAt = millis(created)
		if decided != nil {
			d := millis(*decided)
			e.DecidedAt = &d
		}
		list = append(list, e)
	}
	return list, rows.Err()
}

// adminEnrollments lists enrolments for the admin to review: pending first, then current ones.
func (s *Server) adminEnrollments(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `
		SELECT f.id::text, f.user_id::text, f.status, f.model, f.embeddings, f.photo_verified_at IS NOT NULL, f.created_at, f.decided_at,
		       u.employee_code, u.display_name
		FROM face_enrollments f JOIN users u ON u.id = f.user_id
		WHERE f.status IN ('PENDING', 'APPROVED') AND f.model = $1
		ORDER BY f.status = 'PENDING' DESC, f.created_at DESC`, faceModelName)
	if err != nil {
		s.internal(w, "admin enrollments", err)
		return
	}
	defer rows.Close()
	type item struct {
		enrollmentView
		EmployeeCode string `json:"employee_code"`
		DisplayName  string `json:"display_name"`
	}
	list := []item{}
	for rows.Next() {
		var it item
		var raw []byte
		var created time.Time
		var decided *time.Time
		if err := rows.Scan(&it.ID, &it.UserID, &it.Status, &it.Model, &raw, &it.HasPhoto, &created, &decided, &it.EmployeeCode, &it.DisplayName); err != nil {
			s.internal(w, "admin enrollments", err)
			return
		}
		var emb [][]float32
		_ = json.Unmarshal(raw, &emb)
		it.Shots = len(emb)
		it.CreatedAt = millis(created)
		if decided != nil {
			d := millis(*decided)
			it.DecidedAt = &d
		}
		list = append(list, it)
	}
	if err := rows.Err(); err != nil {
		s.internal(w, "admin enrollments", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"enrollments": list})
}

// decideEnrollment approves or rejects a pending enrolment, or (approve=false on an APPROVED one)
// resets an engineer's face so they must enrol again.
func (s *Server) decideEnrollment(w http.ResponseWriter, r *http.Request) {
	admin := userFrom(r)
	id := r.PathValue("id")
	var req struct {
		Approve bool `json:"approve"`
	}
	if !isUUID(id) {
		writeError(w, http.StatusBadRequest, "INVALID", "id must be a UUID")
		return
	}
	if !readJSON(w, r, 1<<10, &req) {
		return
	}
	var newStatus string
	err := pgx.BeginFunc(r.Context(), s.db, func(tx pgx.Tx) error {
		var userID, status string
		var photoVerified bool
		err := tx.QueryRow(r.Context(), `SELECT user_id::text, status, photo_verified_at IS NOT NULL FROM face_enrollments WHERE id = $1 FOR UPDATE`, id).
			Scan(&userID, &status, &photoVerified)
		if err != nil {
			return err
		}
		switch {
		case req.Approve && status == "PENDING":
			if !photoVerified {
				return errNotReady // the admin must be able to see the face before approving it
			}
			if _, err := tx.Exec(r.Context(), `UPDATE face_enrollments SET status = 'REPLACED' WHERE user_id = $1 AND status = 'APPROVED'`, userID); err != nil {
				return err
			}
			newStatus = "APPROVED"
		case !req.Approve && (status == "PENDING" || status == "APPROVED"):
			newStatus = "REJECTED"
		default:
			return errConflict
		}
		_, err = tx.Exec(r.Context(), `UPDATE face_enrollments SET status = $2, decided_at = now(), decided_by = $3 WHERE id = $1`, id, newStatus, admin.ID)
		return err
	})
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such enrolment")
	case errors.Is(err, errNotReady):
		writeError(w, http.StatusConflict, "PHOTO_MISSING", "the enrolment photo has not finished uploading")
	case errors.Is(err, errConflict):
		writeError(w, http.StatusConflict, "CONFLICT", "this enrolment was already decided")
	case err != nil:
		s.internal(w, "decide enrollment", err)
	default:
		s.auditEvent(r.Context(), "FACE_"+newStatus, &admin.ID, admin.deviceID, "FACE:"+id, "")
		writeJSON(w, http.StatusOK, map[string]any{"id": id, "status": newStatus})
	}
}

var (
	errNotReady = errors.New("not ready")
	errConflict = errors.New("conflict")
)

func (s *Server) enrollmentPhoto(w http.ResponseWriter, r *http.Request) {
	s.serveOwnedObject(w, r, `SELECT user_id::text, photo_key, photo_sha256, false FROM face_enrollments WHERE id = $1 AND photo_verified_at IS NOT NULL`)
}

// serveOwnedObject serves a stored JPEG to its owner or an admin. The query takes the id and
// returns owner, key, sha256 and whether the file was deleted by retention.
func (s *Server) serveOwnedObject(w http.ResponseWriter, r *http.Request, query string) {
	u := userFrom(r)
	id := r.PathValue("id")
	if !isUUID(id) {
		writeError(w, http.StatusBadRequest, "INVALID", "id must be a UUID")
		return
	}
	var owner, hash string
	var key *string
	var deleted bool
	err := s.db.QueryRow(r.Context(), query, id).Scan(&owner, &key, &hash, &deleted)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && key == nil && !deleted) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no stored photo for this id")
		return
	}
	if err != nil {
		s.internal(w, "owned object", err)
		return
	}
	if owner != u.ID && u.Role != "ADMIN" {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no stored photo for this id") // don't reveal others' records
		return
	}
	if deleted {
		writeError(w, http.StatusGone, "EXPIRED", "this selfie was deleted after the retention period")
		return
	}
	f, err := os.Open(filepath.Join(s.cfg.DataDir, *key))
	if err != nil {
		s.internal(w, "owned object", err)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		s.internal(w, "owned object", err)
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("ETag", `"`+hash+`"`)
	w.Header().Set("Cache-Control", "private, max-age=86400")
	http.ServeContent(w, r, "", st.ModTime(), f)
}

// ---- Check-in / check-out ------------------------------------------------------------------

type attendanceIn struct {
	ID             string   `json:"id"`
	UserID         string   `json:"user_id"` // whose record the phone thinks this is; must be the caller
	Kind           string   `json:"kind"`
	ClientAt       int64    `json:"client_at"`
	TimeZone       string   `json:"time_zone"`
	ElapsedMs      *int64   `json:"elapsed_ms"`     // phone's time since boot at the event
	BootCount      *int64   `json:"boot_count"`     // phone's boot counter at the event
	NowElapsedMs   *int64   `json:"now_elapsed_ms"` // ... and right before this request
	NowBootCount   *int64   `json:"now_boot_count"`
	Lat            *float64 `json:"lat"`
	Lng            *float64 `json:"lng"`
	AccuracyM      *float32 `json:"accuracy_m"`
	LocationAt     *int64   `json:"location_at"`
	IsMock         bool     `json:"is_mock"`
	FaceResult     string   `json:"face_result"`
	FaceScore      *float32 `json:"face_score"`
	EnrollmentID   *string  `json:"enrollment_id"`
	LivenessPassed bool     `json:"liveness_passed"`
	SelfieSHA256   string   `json:"selfie_sha256"`
	SelfieSize     int64    `json:"selfie_size"`
}

// estimateEventTime is the server's own clock reading of when the event happened, derived from
// the phone's monotonic clock — immune to the phone's wall clock being changed. nil when the phone
// rebooted between event and upload, or the numbers make no sense.
func estimateEventTime(in attendanceIn, received time.Time) *time.Time {
	if in.ElapsedMs == nil || in.BootCount == nil || in.NowElapsedMs == nil || in.NowBootCount == nil {
		return nil
	}
	if *in.BootCount != *in.NowBootCount {
		return nil
	}
	age := time.Duration(*in.NowElapsedMs-*in.ElapsedMs) * time.Millisecond
	if age < 0 || age > maxEstimateAge {
		return nil
	}
	t := received.Add(-age)
	return &t
}

func (s *Server) recordAttendance(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	var in attendanceIn
	if !readJSON(w, r, 16<<10, &in) {
		return
	}
	switch {
	case !isUUID(in.ID), in.Kind != "IN" && in.Kind != "OUT", in.ClientAt <= 0, in.TimeZone == "", len(in.TimeZone) > 64,
		!faceResults[in.FaceResult], len(in.SelfieSHA256) != 64, in.SelfieSize <= 0, in.SelfieSize > s.cfg.MaxUploadBytes,
		(in.Lat == nil) != (in.Lng == nil),
		in.Lat != nil && (*in.Lat < -90 || *in.Lat > 90 || *in.Lng < -180 || *in.Lng > 180),
		in.EnrollmentID != nil && !isUUID(*in.EnrollmentID):
		writeError(w, http.StatusBadRequest, "INVALID", "invalid attendance record")
		return
	}
	// A phone shared by two engineers must never file one's offline check-in under the other's
	// session. Older clients don't send user_id; they only ever upload their own user's rows.
	if in.UserID != "" && in.UserID != u.ID {
		writeError(w, http.StatusForbidden, "WRONG_USER", "this record belongs to another account; it uploads when they sign in")
		return
	}
	now := s.now()
	estimated := estimateEventTime(in, now)
	tag, err := s.db.Exec(r.Context(), `
		INSERT INTO attendance (id, user_id, device_id, kind, client_at, time_zone, received_at, estimated_at, lat, lng, accuracy_m, location_at,
			is_mock, face_result, face_score, enrollment_id, liveness_passed, selfie_sha256, selfie_size)
		SELECT $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15,
			(SELECT id FROM face_enrollments WHERE id = $16::uuid AND user_id = $2), $17, lower($18), $19
		ON CONFLICT (id) DO NOTHING`,
		in.ID, u.ID, u.deviceID, in.Kind, in.ClientAt, in.TimeZone, now, estimated, in.Lat, in.Lng, in.AccuracyM, in.LocationAt,
		in.IsMock, in.FaceResult, in.FaceScore, in.EnrollmentID, in.LivenessPassed, in.SelfieSHA256, in.SelfieSize)
	if err != nil {
		s.internal(w, "record attendance", err)
		return
	}
	if tag.RowsAffected() == 0 {
		// A retry, or someone else's id: only the owner gets a success.
		var owner string
		if err := s.db.QueryRow(r.Context(), `SELECT user_id::text FROM attendance WHERE id = $1`, in.ID).Scan(&owner); err != nil || owner != u.ID {
			writeError(w, http.StatusConflict, "CONFLICT", "id already used")
			return
		}
	} else {
		s.auditEvent(r.Context(), "ATTENDANCE_"+in.Kind, &u.ID, u.deviceID, "ATTENDANCE:"+in.ID, "face="+in.FaceResult)
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": in.ID, "received_at": millis(now)})
}

func (s *Server) uploadSelfie(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	id := r.PathValue("id")
	if !isUUID(id) {
		writeError(w, http.StatusBadRequest, "INVALID", "id must be a UUID")
		return
	}
	var owner, wantHash string
	var wantSize int64
	var key *string
	var verifiedAt *time.Time
	var received time.Time
	err := s.db.QueryRow(r.Context(), `SELECT user_id::text, selfie_sha256, selfie_size, selfie_key, selfie_verified_at, received_at FROM attendance WHERE id = $1`, id).
		Scan(&owner, &wantHash, &wantSize, &key, &verifiedAt, &received)
	if errors.Is(err, pgx.ErrNoRows) {
		drain(r, s.cfg.MaxUploadBytes)
		writeError(w, http.StatusConflict, "METADATA_MISSING", "record the check-in before uploading its selfie")
		return
	}
	if err != nil {
		s.internal(w, "selfie", err)
		return
	}
	if owner != u.ID {
		drain(r, s.cfg.MaxUploadBytes)
		writeError(w, http.StatusForbidden, "FORBIDDEN", "not your record")
		return
	}
	s.receiveObject(w, r, id, wantHash, wantSize, key, verifiedAt,
		filepath.Join("attendance", owner, received.UTC().Format("2006-01"), id+".jpg"), "ATTENDANCE:"+id,
		`UPDATE attendance SET selfie_key = $2, selfie_verified_at = $3 WHERE id = $1`)
}

func (s *Server) attendanceSelfie(w http.ResponseWriter, r *http.Request) {
	s.serveOwnedObject(w, r, `SELECT user_id::text, selfie_key, selfie_sha256, selfie_deleted_at IS NOT NULL FROM attendance WHERE id = $1`)
}

type attendanceOut struct {
	ID             string   `json:"id"`
	UserID         string   `json:"user_id"`
	EmployeeCode   string   `json:"employee_code"`
	DisplayName    string   `json:"display_name"`
	Kind           string   `json:"kind"`
	ClientAt       int64    `json:"client_at"`
	TimeZone       string   `json:"time_zone"`
	ReceivedAt     int64    `json:"received_at"`
	EstimatedAt    *int64   `json:"estimated_at"`
	Lat            *float64 `json:"lat"`
	Lng            *float64 `json:"lng"`
	AccuracyM      *float32 `json:"accuracy_m"`
	IsMock         bool     `json:"is_mock"`
	FaceResult     string   `json:"face_result"`
	FaceScore      *float32 `json:"face_score"`
	EnrollmentID   *string  `json:"enrollment_id"`
	LivenessPassed bool     `json:"liveness_passed"`
	Selfie         string   `json:"selfie"` // AVAILABLE | UPLOADING | DELETED
}

// listAttendance: GET /v1/attendance?from=&to= (epoch ms, phone clock) — the caller's own records;
// GET /v1/admin/attendance?from=&to=[&user=] — everyone's.
func (s *Server) listAttendance(w http.ResponseWriter, r *http.Request) {
	s.attendanceRows(w, r, false)
}
func (s *Server) adminListAttendance(w http.ResponseWriter, r *http.Request) {
	s.attendanceRows(w, r, true)
}

func (s *Server) attendanceRows(w http.ResponseWriter, r *http.Request, admin bool) {
	q := r.URL.Query()
	from, err1 := strconv.ParseInt(q.Get("from"), 10, 64)
	to, err2 := strconv.ParseInt(q.Get("to"), 10, 64)
	if err1 != nil || err2 != nil || to <= from || to-from > int64(62*24*time.Hour/time.Millisecond) {
		writeError(w, http.StatusBadRequest, "INVALID", "from/to must be epoch ms, at most 62 days apart")
		return
	}
	user := userFrom(r).ID
	if admin {
		user = q.Get("user")
		if user != "" && !isUUID(user) {
			writeError(w, http.StatusBadRequest, "INVALID", "user must be a UUID")
			return
		}
	}
	rows, err := s.db.Query(r.Context(), `
		SELECT a.id::text, a.user_id::text, u.employee_code, u.display_name, a.kind, a.client_at, a.time_zone, a.received_at, a.estimated_at,
		       a.lat, a.lng, a.accuracy_m, a.is_mock, a.face_result, a.face_score, a.enrollment_id::text, a.liveness_passed,
		       CASE WHEN a.selfie_deleted_at IS NOT NULL THEN 'DELETED' WHEN a.selfie_verified_at IS NOT NULL THEN 'AVAILABLE' ELSE 'UPLOADING' END
		FROM attendance a JOIN users u ON u.id = a.user_id
		WHERE a.client_at >= $1 AND a.client_at < $2 AND ($3 = '' OR a.user_id::text = $3)
		ORDER BY a.client_at
		LIMIT 5000`, from, to, user)
	if err != nil {
		s.internal(w, "attendance", err)
		return
	}
	defer rows.Close()
	list := []attendanceOut{}
	for rows.Next() {
		var a attendanceOut
		var received time.Time
		var estimated *time.Time
		if err := rows.Scan(&a.ID, &a.UserID, &a.EmployeeCode, &a.DisplayName, &a.Kind, &a.ClientAt, &a.TimeZone, &received, &estimated,
			&a.Lat, &a.Lng, &a.AccuracyM, &a.IsMock, &a.FaceResult, &a.FaceScore, &a.EnrollmentID, &a.LivenessPassed, &a.Selfie); err != nil {
			s.internal(w, "attendance", err)
			return
		}
		a.ReceivedAt = millis(received)
		if estimated != nil {
			e := millis(*estimated)
			a.EstimatedAt = &e
		}
		list = append(list, a)
	}
	if err := rows.Err(); err != nil {
		s.internal(w, "attendance", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"records": list})
}

// ---- Selfie retention ----------------------------------------------------------------------

// PurgeOldSelfies deletes check-in selfies older than selfieRetention (by arrival at the server)
// and marks their rows; the attendance records themselves are kept. Returns how many it removed.
func (s *Server) PurgeOldSelfies(ctx context.Context) (int, error) {
	cutoff := s.now().Add(-selfieRetention)
	rows, err := s.db.Query(ctx, `SELECT id::text, selfie_key FROM attendance
		WHERE selfie_key IS NOT NULL AND selfie_deleted_at IS NULL AND received_at < $1 LIMIT 1000`, cutoff)
	if err != nil {
		return 0, err
	}
	type victim struct{ id, key string }
	var victims []victim
	for rows.Next() {
		var v victim
		if err := rows.Scan(&v.id, &v.key); err != nil {
			rows.Close()
			return 0, err
		}
		victims = append(victims, v)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	removed := 0
	for _, v := range victims {
		if err := os.Remove(filepath.Join(s.cfg.DataDir, v.key)); err != nil && !errors.Is(err, os.ErrNotExist) {
			s.log.Error("selfie purge", "id", v.id, "err", err)
			continue
		}
		if _, err := s.db.Exec(ctx, `UPDATE attendance SET selfie_deleted_at = now() WHERE id = $1`, v.id); err != nil {
			return removed, err
		}
		removed++
	}
	if removed > 0 {
		s.auditEvent(ctx, "SELFIES_PURGED", nil, nil, "", fmt.Sprintf("count=%d", removed))
	}
	return removed, nil
}

// RunSelfieRetention purges expired selfies now and then every 6 hours until ctx ends.
func (s *Server) RunSelfieRetention(ctx context.Context) {
	tick := time.NewTicker(6 * time.Hour)
	defer tick.Stop()
	for {
		if n, err := s.PurgeOldSelfies(ctx); err != nil {
			s.log.Error("selfie retention", "err", err)
		} else if n > 0 {
			s.log.Info("selfie retention", "removed", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}
