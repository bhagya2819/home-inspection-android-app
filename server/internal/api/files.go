package api

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// Objects are stored as DATA_DIR/<kind>/<inspection_id>/<id>.<ext>, written to DATA_DIR/tmp first.
// An object is "verified" only after: bytes streamed to a temp file while hashed → size and SHA-256
// equal what the client recorded at capture → fsync → atomic rename → DB row updated. Only then
// does the response tell the client it may mark the photo as backed up (PRD §8.2 steps 4–6).

type objectKind struct {
	entity   *entity
	dir      string
	ext      string
	mime     string
	hashCol  string
	auditTyp string
}

var (
	photoObjects  = objectKind{entityByType["PHOTO"], "photos", "jpg", "image/jpeg", "sha256", "PHOTO_BACKED_UP"}
	reportObjects = objectKind{entityByType["REPORT"], "reports", "pdf", "application/pdf", "content_hash", "REPORT_BACKED_UP"}
)

func (s *Server) uploadPhoto(w http.ResponseWriter, r *http.Request)  { s.upload(w, r, photoObjects) }
func (s *Server) uploadReport(w http.ResponseWriter, r *http.Request) { s.upload(w, r, reportObjects) }
func (s *Server) downloadPhoto(w http.ResponseWriter, r *http.Request) {
	s.download(w, r, photoObjects)
}
func (s *Server) downloadReport(w http.ResponseWriter, r *http.Request) {
	if userFrom(r).web && isUUID(r.PathValue("id")) { // a browser saves it under the phone's file name
		w.Header().Set("Content-Disposition", `attachment; filename="`+s.reportFileName(r, r.PathValue("id"), "pdf")+`"`)
	}
	s.download(w, r, reportObjects)
}

func (s *Server) upload(w http.ResponseWriter, r *http.Request, kind objectKind) {
	u := userFrom(r)
	id := r.PathValue("id")
	if !isUUID(id) {
		writeError(w, http.StatusBadRequest, "INVALID", "id must be a UUID")
		return
	}
	var inspectionID, wantHash string
	var wantSize int64
	var objectKey *string
	var verifiedAt *time.Time
	err := s.db.QueryRow(r.Context(), fmt.Sprintf(
		`SELECT inspection_id::text, %s, file_size_bytes, object_key, verified_at FROM %s WHERE id = $1`, kind.hashCol, kind.entity.table), id,
	).Scan(&inspectionID, &wantHash, &wantSize, &objectKey, &verifiedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		// Metadata must be pushed first; the client retries the upload after its next push.
		drain(r, s.cfg.MaxUploadBytes)
		writeError(w, http.StatusConflict, "METADATA_MISSING", "push the record before uploading its file")
		return
	}
	if err != nil {
		s.internal(w, "upload", err)
		return
	}
	if objectKey != nil && verifiedAt != nil {
		// Idempotent: a retry after a lost response gets the same answer.
		drain(r, s.cfg.MaxUploadBytes)
		writeJSON(w, http.StatusOK, verifiedBody(*objectKey, wantHash, wantSize, *verifiedAt))
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

	key := filepath.Join(kind.dir, inspectionID, id+"."+kind.ext)
	n, got, ok := s.storeVerified(w, r, id, wantSize, wantHash, key, kind.entity.typ+":"+id)
	if !ok {
		return
	}
	now := s.now()
	err = pgx.BeginFunc(r.Context(), s.db, func(tx pgx.Tx) error {
		if err := writeLock(r.Context(), tx); err != nil {
			return err
		}
		_, err := tx.Exec(r.Context(), fmt.Sprintf(
			`UPDATE %s SET object_key = $2, verified_at = $3, change_seq = nextval('change_seq') WHERE id = $1`, kind.entity.table),
			id, key, now)
		return err
	})
	if err != nil {
		s.internal(w, "upload", err)
		return
	}
	s.auditEvent(r.Context(), kind.auditTyp, &u.ID, u.deviceID, kind.entity.typ+":"+id, "bytes="+strconv.FormatInt(n, 10))
	writeJSON(w, http.StatusOK, verifiedBody(key, got, n, now))
}

// storeVerified streams the request body to DATA_DIR/tmp while hashing it, checks size and
// SHA-256 against what the client recorded, fsyncs, and atomically renames it to DATA_DIR/key.
// On failure it has already answered the request; ok is false.
func (s *Server) storeVerified(w http.ResponseWriter, r *http.Request, id string, wantSize int64, wantHash, key, auditEntity string) (n int64, hash string, ok bool) {
	u := userFrom(r)
	tmpDir := filepath.Join(s.cfg.DataDir, "tmp")
	if err := os.MkdirAll(tmpDir, 0o750); err != nil {
		s.internal(w, "upload", err)
		return 0, "", false
	}
	suffix := make([]byte, 8)
	_, _ = rand.Read(suffix)
	tmp := filepath.Join(tmpDir, id+"."+hex.EncodeToString(suffix)+".part")
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o640)
	if err != nil {
		s.internal(w, "upload", err)
		return 0, "", false
	}
	defer os.Remove(tmp) // no-op after the rename succeeds
	h := sha256.New()
	n, copyErr := io.Copy(io.MultiWriter(f, h), http.MaxBytesReader(w, r.Body, wantSize+1))
	syncErr := f.Sync()
	closeErr := f.Close()
	if copyErr != nil {
		writeError(w, http.StatusBadRequest, "UPLOAD_INTERRUPTED", "upload did not complete; retry")
		return 0, "", false
	}
	if syncErr != nil || closeErr != nil {
		s.internal(w, "upload", errors.Join(syncErr, closeErr))
		return 0, "", false
	}
	got := hex.EncodeToString(h.Sum(nil))
	if n != wantSize || !strings.EqualFold(got, wantHash) {
		s.log.Warn("upload checksum mismatch", "entity", auditEntity, "size", n, "want_size", wantSize)
		s.auditEvent(r.Context(), "UPLOAD_CHECKSUM_MISMATCH", &u.ID, u.deviceID, auditEntity, "bytes="+strconv.FormatInt(n, 10))
		writeError(w, http.StatusUnprocessableEntity, "CHECKSUM_MISMATCH", "received bytes do not match the recorded size/SHA-256")
		return 0, "", false
	}
	final := filepath.Join(s.cfg.DataDir, key)
	if err := ensureDir(filepath.Dir(final)); err != nil {
		s.internal(w, "upload", err)
		return 0, "", false
	}
	if err := os.Rename(tmp, final); err != nil {
		s.internal(w, "upload", err)
		return 0, "", false
	}
	if err := syncDir(filepath.Dir(final)); err != nil {
		s.internal(w, "upload", err)
		return 0, "", false
	}
	return n, got, true
}

// drain reads the rest of a rejected upload so the client receives our error response.
// Answering before the body is consumed makes the proxy reset the connection, and the phone
// would only see "connection reset" (indistinguishable from being offline).
func drain(r *http.Request, limit int64) {
	_, _ = io.Copy(io.Discard, io.LimitReader(r.Body, limit))
}

func verifiedBody(key, hash string, size int64, at time.Time) map[string]any {
	return map[string]any{"object_key": key, "sha256": strings.ToLower(hash), "size": size, "verified_at": millis(at)}
}

// download serves a verified object with Range support, so interrupted downloads resume.
func (s *Server) download(w http.ResponseWriter, r *http.Request, kind objectKind) {
	id := r.PathValue("id")
	if !isUUID(id) {
		writeError(w, http.StatusBadRequest, "INVALID", "id must be a UUID")
		return
	}
	var key, hash *string
	var archivedExport *int64
	err := s.db.QueryRow(r.Context(), fmt.Sprintf(`SELECT object_key, %s, archived_export_id FROM %s WHERE id = $1 AND verified_at IS NOT NULL`,
		kind.hashCol, kind.entity.table), id).Scan(&key, &hash, &archivedExport)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && key == nil) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no backed-up file for this id")
		return
	}
	if err != nil {
		s.internal(w, "download", err)
		return
	}
	if archivedExport != nil {
		// Removed from the server after a verified export (M5). Not an error to retry.
		writeJSON(w, http.StatusGone, map[string]any{"error": map[string]any{
			"code": "ARCHIVED", "message": "this file was moved to an offline export archive", "export_id": *archivedExport}})
		return
	}
	f, err := os.Open(filepath.Join(s.cfg.DataDir, *key))
	if err != nil {
		s.internal(w, "download", err)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		s.internal(w, "download", err)
		return
	}
	w.Header().Set("Content-Type", kind.mime)
	w.Header().Set("ETag", `"`+*hash+`"`)
	w.Header().Set("X-Content-SHA256", *hash)
	http.ServeContent(w, r, "", st.ModTime(), f)
}

// checkPhotos lets a phone confirm, right before it removes local originals (retention), that the
// server still holds each photo: the file exists with the recorded size (a full re-hash of every
// file per request would be too slow; exports re-hash). ARCHIVED means the copy lives in an
// admin-verified export instead.
func (s *Server) checkPhotos(w http.ResponseWriter, r *http.Request) {
	var req struct {
		IDs []string `json:"ids"`
	}
	if !readJSON(w, r, 64<<10, &req) {
		return
	}
	if len(req.IDs) > 500 {
		writeError(w, http.StatusRequestEntityTooLarge, "TOO_MANY_ITEMS", "check at most 500 photos per request")
		return
	}
	results := map[string]any{}
	valid := make([]string, 0, len(req.IDs))
	for _, id := range req.IDs {
		if isUUID(id) {
			valid = append(valid, id)
			results[id] = map[string]string{"status": "MISSING"}
		}
	}
	rows, err := s.db.Query(r.Context(), `SELECT id::text, object_key, file_size_bytes, verified_at IS NOT NULL, archived_export_id IS NOT NULL
		FROM photos WHERE id::text = ANY($1)`, valid)
	if err != nil {
		s.internal(w, "check photos", err)
		return
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		var key *string
		var size int64
		var verified, archived bool
		if err := rows.Scan(&id, &key, &size, &verified, &archived); err != nil {
			s.internal(w, "check photos", err)
			return
		}
		status := "OK"
		switch {
		case archived:
			status = "ARCHIVED"
		case !verified || key == nil:
			status = "NOT_VERIFIED"
		default:
			st, err := os.Stat(filepath.Join(s.cfg.DataDir, *key))
			if err != nil {
				status = "MISSING"
			} else if st.Size() != size {
				status = "SIZE_MISMATCH"
			}
			if status != "OK" {
				s.log.Error("verified photo unavailable", "id", id, "status", status)
			}
		}
		results[id] = map[string]string{"status": status}
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": results})
}
