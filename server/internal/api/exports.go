package api

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/csv"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
)

// Admin export + archive (M5, replaces paid server storage). An admin's phone downloads month-sized
// zips of verified photos and reports, re-hashes every entry against the manifest, confirms that to
// the server, and only then may the server remove old object files. DB records are never removed:
// an archived object answers 410 ARCHIVED and the zip (kept on Drive + a laptop) is the copy.
//
// Lifecycle: CREATED → VERIFIED (client re-hashed every entry) → ARCHIVED (some files removed).

const (
	exportTargetBytes = 1_500_000_000 // ~1.5 GB per zip: finishes on mobile data, fits phone storage
	exportWarnBytes   = 5_000_000_000 // admin warning: this much backed up since the last export
	// A link dies this long after its last use: an active (resuming) download keeps it alive,
	// a link left in a chat or a log goes dead within half an hour.
	downloadLinkTTL = 30 * time.Minute
)

type exportInfo struct {
	ID              int64  `json:"id"`
	Label           string `json:"label"`
	Status          string `json:"status"`
	CreatedAt       int64  `json:"created_at"`
	EntryCount      int    `json:"entry_count"`
	ObjectsBytes    int64  `json:"objects_bytes"`
	ZipBytes        int64  `json:"zip_bytes"`
	ManifestSHA256  string `json:"manifest_sha256"`
	VerifiedAt      *int64 `json:"verified_at"`
	ArchivedAt      *int64 `json:"archived_at"`
	ArchivedObjects int    `json:"archived_objects"`
	FileName        string `json:"file_name"`
}

const exportColumns = `e.id, e.label, e.status, (extract(epoch FROM e.created_at) * 1000)::bigint, e.entry_count, e.objects_bytes,
	e.zip_bytes, e.manifest_sha256, (extract(epoch FROM e.verified_at) * 1000)::bigint, (extract(epoch FROM e.archived_at) * 1000)::bigint,
	(SELECT count(*) FROM photos p WHERE p.archived_export_id = e.id) + (SELECT count(*) FROM reports r WHERE r.archived_export_id = e.id)`

func scanExport(row pgx.Row) (exportInfo, error) {
	var e exportInfo
	var archived int64
	err := row.Scan(&e.ID, &e.Label, &e.Status, &e.CreatedAt, &e.EntryCount, &e.ObjectsBytes, &e.ZipBytes, &e.ManifestSHA256,
		&e.VerifiedAt, &e.ArchivedAt, &archived)
	e.ArchivedObjects = int(archived)
	e.FileName = fmt.Sprintf("griha-export-%s-%d.zip", strings.ReplaceAll(e.Label, " ", "-"), e.ID)
	return e, err
}

func (s *Server) loadExport(ctx context.Context, q interface {
	QueryRow(context.Context, string, ...any) pgx.Row
}, id int64) (exportInfo, error) {
	return scanExport(q.QueryRow(ctx, `SELECT `+exportColumns+` FROM exports e WHERE e.id = $1`, id))
}

// ---- Storage overview ------------------------------------------------------------------------

func diskSpace(dir string) (free, total int64) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(dir, &st); err != nil {
		return -1, -1
	}
	return int64(st.Bavail) * int64(st.Bsize), int64(st.Blocks) * int64(st.Bsize)
}

func (s *Server) storage(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	free, total := diskSpace(s.cfg.DataDir)
	out := map[string]any{"data_free_bytes": free, "data_total_bytes": total, "export_warn_bytes": int64(exportWarnBytes)}
	var objects, unexported, archived, unexportedCount int64
	err := s.db.QueryRow(ctx, `
		WITH o AS (
			SELECT file_size_bytes AS size, export_verified_at, archived_at FROM photos WHERE verified_at IS NOT NULL
			UNION ALL
			SELECT file_size_bytes, export_verified_at, archived_at FROM reports WHERE verified_at IS NOT NULL
		)
		SELECT COALESCE(sum(size) FILTER (WHERE archived_at IS NULL), 0)::bigint,
		       COALESCE(sum(size) FILTER (WHERE archived_at IS NULL AND export_verified_at IS NULL), 0)::bigint,
		       COALESCE(sum(size) FILTER (WHERE archived_at IS NOT NULL), 0)::bigint,
		       count(*) FILTER (WHERE archived_at IS NULL AND export_verified_at IS NULL)
		FROM o`).Scan(&objects, &unexported, &archived, &unexportedCount)
	if err != nil {
		s.internal(w, "storage", err)
		return
	}
	var lastExport *int64
	if err := s.db.QueryRow(ctx, `SELECT (extract(epoch FROM max(verified_at)) * 1000)::bigint FROM exports`).Scan(&lastExport); err != nil {
		s.internal(w, "storage", err)
		return
	}
	rows, err := s.db.Query(ctx, `SELECT `+exportColumns+` FROM exports e ORDER BY e.id DESC LIMIT 24`)
	if err != nil {
		s.internal(w, "storage", err)
		return
	}
	exports := []exportInfo{}
	for rows.Next() {
		e, err := scanExport(rows)
		if err != nil {
			rows.Close()
			s.internal(w, "storage", err)
			return
		}
		exports = append(exports, e)
	}
	rows.Close()
	out["objects_bytes"] = objects
	out["unexported_bytes"] = unexported
	out["unexported_count"] = unexportedCount
	out["archived_bytes"] = archived
	out["last_export_at"] = lastExport
	out["archive_min_age_days"] = s.settingInt(ctx, "archive_min_age_days")
	out["exports"] = exports
	writeJSON(w, http.StatusOK, out)
}

// ---- Create ----------------------------------------------------------------------------------

type exportCandidate struct {
	kind, id, inspectionID, month, key, sha string
	size, takenAt                           int64
}

type objectProblem struct {
	Kind    string `json:"kind"`
	ID      string `json:"id"`
	Problem string `json:"problem"` // MISSING | SIZE_MISMATCH | CHECKSUM_MISMATCH
}

// createExport freezes the next batch: the oldest month (by inspection date) that has backed-up
// objects not yet in a verified export, whole inspections at a time, up to ~1.5 GB. While an
// export is still unverified it is returned again instead of creating an overlapping one.
func (s *Server) createExport(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	admin := userFrom(r)
	var info exportInfo
	var problems []objectProblem
	var nothing bool
	err := pgx.BeginFunc(ctx, s.db, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(7003)`); err != nil {
			return err
		}
		var open int64
		err := tx.QueryRow(ctx, `SELECT id FROM exports WHERE status = 'CREATED' ORDER BY id LIMIT 1`).Scan(&open)
		if err == nil {
			info, err = s.loadExport(ctx, tx, open)
			return err
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		candidates, err := s.exportCandidates(ctx, tx)
		if err != nil {
			return err
		}
		if len(candidates) == 0 {
			nothing = true
			return nil
		}
		var id int64
		if err := tx.QueryRow(ctx, `SELECT nextval('exports_id_seq')`).Scan(&id); err != nil {
			return err
		}
		created := s.now().UTC().Truncate(time.Second)
		info, problems, err = s.buildExport(ctx, tx, id, admin.ID, created, candidates)
		return err
	})
	if err != nil {
		s.internal(w, "create export", err)
		return
	}
	if nothing {
		writeError(w, http.StatusConflict, "NOTHING_TO_EXPORT", "every backed-up file is already in a verified export")
		return
	}
	if len(problems) > 0 {
		raw, _ := json.Marshal(problems)
		s.log.Error("export integrity", "problems", string(raw))
		s.auditEvent(ctx, "BACKUP_INTEGRITY_FAILED", &admin.ID, admin.deviceID, "", truncate(string(raw), 2000))
	}
	if info.ID == 0 {
		writeError(w, http.StatusConflict, "NOTHING_TO_EXPORT", "no readable backed-up files to export")
		return
	}
	s.auditEvent(ctx, "EXPORT_CREATED", &admin.ID, admin.deviceID, "EXPORT:"+strconv.FormatInt(info.ID, 10),
		fmt.Sprintf("label=%s entries=%d bytes=%d", info.Label, info.EntryCount, info.ZipBytes))
	writeJSON(w, http.StatusOK, map[string]any{"export": info, "problems": problems})
}

func (s *Server) exportCandidates(ctx context.Context, tx pgx.Tx) ([]exportCandidate, error) {
	rows, err := tx.Query(ctx, `
		SELECT o.kind, o.id::text, o.inspection_id::text, left(i.inspection_date, 7), o.object_key, lower(o.sha), o.size, o.ts
		FROM (
			SELECT 'PHOTO' AS kind, id, inspection_id, object_key, sha256 AS sha, file_size_bytes AS size, captured_at AS ts FROM photos
			WHERE verified_at IS NOT NULL AND object_key IS NOT NULL AND export_verified_at IS NULL AND archived_at IS NULL
			UNION ALL
			SELECT 'REPORT', id, inspection_id, object_key, content_hash, file_size_bytes, generated_at FROM reports
			WHERE verified_at IS NOT NULL AND object_key IS NOT NULL AND export_verified_at IS NULL AND archived_at IS NULL
		) o JOIN inspections i ON i.id = o.inspection_id
		ORDER BY left(i.inspection_date, 7), i.created_at, i.id, o.kind, o.ts, o.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var all []exportCandidate
	for rows.Next() {
		var c exportCandidate
		if err := rows.Scan(&c.kind, &c.id, &c.inspectionID, &c.month, &c.key, &c.sha, &c.size, &c.takenAt); err != nil {
			return nil, err
		}
		all = append(all, c)
	}
	if err := rows.Err(); err != nil || len(all) == 0 {
		return nil, err
	}
	// The oldest month only, whole inspections, stopping once the batch reaches the target size.
	month := all[0].month
	var batch []exportCandidate
	var total int64
	for i, c := range all {
		if c.month != month {
			break
		}
		if i > 0 && c.inspectionID != all[i-1].inspectionID && total >= exportTargetBytes {
			break
		}
		batch = append(batch, c)
		total += c.size
	}
	return batch, nil
}

// buildExport hashes every object (SHA-256 against the DB, CRC-32 for the zip header), writes
// manifest.csv and data.json, and stores the frozen layout. Unreadable or altered objects are left
// out and reported, never exported as if they were good.
func (s *Server) buildExport(ctx context.Context, tx pgx.Tx, id int64, adminID string, created time.Time, candidates []exportCandidate) (exportInfo, []objectProblem, error) {
	type item struct {
		c   exportCandidate
		crc uint32
	}
	var items []item
	var problems []objectProblem
	inspections := map[string]bool{}
	var inspectionOrder []string
	for _, c := range candidates {
		crc, problem := hashObject(filepath.Join(s.cfg.DataDir, c.key), c.size, c.sha)
		if problem != "" {
			problems = append(problems, objectProblem{c.kind, c.id, problem})
			continue
		}
		items = append(items, item{c, crc})
		if !inspections[c.inspectionID] {
			inspections[c.inspectionID] = true
			inspectionOrder = append(inspectionOrder, c.inspectionID)
		}
	}
	if len(items) == 0 {
		return exportInfo{}, problems, nil
	}
	label := candidates[0].month
	dataJSON, err := s.exportData(ctx, tx, id, label, created, inspectionOrder)
	if err != nil {
		return exportInfo{}, nil, err
	}
	dataSum := sha256.Sum256(dataJSON)

	var manifest bytes.Buffer
	cw := csv.NewWriter(&manifest)
	_ = cw.Write([]string{"path", "sha256", "size", "inspection_id", "captured_at"})
	_ = cw.Write([]string{"data.json", hex.EncodeToString(dataSum[:]), strconv.Itoa(len(dataJSON)), "", ""})
	var objectsBytes int64
	for _, it := range items {
		objectsBytes += it.c.size
		_ = cw.Write([]string{it.c.key, it.c.sha, strconv.FormatInt(it.c.size, 10), it.c.inspectionID,
			time.UnixMilli(it.c.takenAt).UTC().Format(time.RFC3339)})
	}
	cw.Flush()
	manifestBytes := manifest.Bytes()
	manifestSum := sha256.Sum256(manifestBytes)

	entries := exportEntries(s.cfg.DataDir, manifestBytes, dataJSON, nil)
	seqs := make([]int32, len(items))
	kinds := make([]string, len(items))
	ids := make([]string, len(items))
	paths := make([]string, len(items))
	sizes := make([]int64, len(items))
	shas := make([]string, len(items))
	crcs := make([]int64, len(items))
	for i, it := range items {
		seqs[i], kinds[i], ids[i], paths[i], sizes[i], shas[i], crcs[i] = int32(i), it.c.kind, it.c.id, it.c.key, it.c.size, it.c.sha, int64(it.crc)
		entries = append(entries, zipEntry{name: it.c.key, size: it.c.size, crc: it.crc, file: filepath.Join(s.cfg.DataDir, it.c.key)})
	}
	layout, err := newZipLayout(entries, created)
	if err != nil {
		return exportInfo{}, nil, err
	}
	_, err = tx.Exec(ctx, `INSERT INTO exports (id, label, created_by, created_at, entry_count, objects_bytes, zip_bytes, manifest, manifest_sha256, data_json)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
		id, label, adminID, created, len(entries), objectsBytes, layout.Size(), manifestBytes, hex.EncodeToString(manifestSum[:]), dataJSON)
	if err != nil {
		return exportInfo{}, nil, err
	}
	_, err = tx.Exec(ctx, `
		INSERT INTO export_items (export_id, seq, kind, object_id, path, size, sha256, crc32)
		SELECT $1, t.seq, t.kind, t.oid::uuid, t.path, t.size, t.sha, t.crc
		FROM unnest($2::int[], $3::text[], $4::text[], $5::text[], $6::bigint[], $7::text[], $8::bigint[]) AS t(seq, kind, oid, path, size, sha, crc)`,
		id, seqs, kinds, ids, paths, sizes, shas, crcs)
	if err != nil {
		return exportInfo{}, nil, err
	}
	info, err := s.loadExport(ctx, tx, id)
	return info, problems, err
}

// hashObject reads a stored object once: its size and SHA-256 must still match what the phone
// recorded at capture; the CRC-32 goes into the zip header.
func hashObject(path string, size int64, sha string) (uint32, string) {
	f, err := os.Open(path)
	if err != nil {
		return 0, "MISSING"
	}
	defer f.Close()
	h := sha256.New()
	c := crc32.NewIEEE()
	n, err := io.Copy(io.MultiWriter(h, c), f)
	if err != nil {
		return 0, "MISSING"
	}
	if n != size {
		return 0, "SIZE_MISMATCH"
	}
	if hex.EncodeToString(h.Sum(nil)) != sha {
		return 0, "CHECKSUM_MISMATCH"
	}
	return c.Sum32(), ""
}

// exportEntries returns manifest.csv and data.json (first, so a reader sees what to expect),
// followed by the given object entries.
func exportEntries(dataDir string, manifest, dataJSON []byte, objects []zipEntry) []zipEntry {
	out := []zipEntry{
		{name: "manifest.csv", size: int64(len(manifest)), crc: crc32.ChecksumIEEE(manifest), data: manifest},
		{name: "data.json", size: int64(len(dataJSON)), crc: crc32.ChecksumIEEE(dataJSON), data: dataJSON},
	}
	return append(out, objects...)
}

// exportData is a readable snapshot of the exported inspections: every record, nested per
// inspection, readable without the app. Frozen at creation (stored with the export).
func (s *Server) exportData(ctx context.Context, tx pgx.Tx, id int64, label string, created time.Time, inspectionIDs []string) ([]byte, error) {
	load := func(e *entity, where string) ([]map[string]any, error) {
		rows, err := tx.Query(ctx, fmt.Sprintf(`SELECT %s FROM %s WHERE %s ORDER BY created_at, id`, e.selectList(), e.table, where), inspectionIDs)
		if err != nil {
			return nil, err
		}
		maps, err := rowsToMaps(rows)
		for _, m := range maps {
			delete(m, "change_seq")
		}
		return maps, err
	}
	inspections, err := load(entityByType["INSPECTION"], `id::text = ANY($1)`)
	if err != nil {
		return nil, err
	}
	children := map[string][]map[string]any{}
	for _, typ := range []string{"ROOM", "FINDING", "PHOTO"} {
		if children[typ], err = load(entityByType[typ], `inspection_id::text = ANY($1)`); err != nil {
			return nil, err
		}
	}
	reportRows, err := tx.Query(ctx, fmt.Sprintf(`SELECT %s FROM reports WHERE inspection_id::text = ANY($1) ORDER BY version, id`,
		entityByType["REPORT"].selectList()), inspectionIDs)
	if err != nil {
		return nil, err
	}
	if children["REPORT"], err = rowsToMaps(reportRows); err != nil {
		return nil, err
	}
	for _, m := range children["REPORT"] {
		delete(m, "change_seq")
	}
	byInspection := func(typ, inspectionID string) []map[string]any {
		out := []map[string]any{}
		for _, m := range children[typ] {
			if m["inspection_id"] == inspectionID {
				out = append(out, m)
			}
		}
		return out
	}
	for _, insp := range inspections {
		iid, _ := insp["id"].(string)
		insp["rooms"] = byInspection("ROOM", iid)
		insp["findings"] = byInspection("FINDING", iid)
		insp["photos"] = byInspection("PHOTO", iid)
		insp["reports"] = byInspection("REPORT", iid)
	}
	userRows, err := tx.Query(ctx, `SELECT id::text AS id, employee_code, display_name, role FROM users ORDER BY employee_code`)
	if err != nil {
		return nil, err
	}
	users, err := rowsToMaps(userRows)
	if err != nil {
		return nil, err
	}
	return json.MarshalIndent(map[string]any{
		"format":      "griha-export/1",
		"export":      map[string]any{"id": id, "label": label, "created_at": created.Format(time.RFC3339)},
		"notes":       "Timestamps are UTC epoch milliseconds. Photo files are photos/<inspection_id>/<photo_id>.jpg; report PDFs are reports/<inspection_id>/<report_id>.pdf. manifest.csv lists the SHA-256 of every file.",
		"users":       users,
		"inspections": inspections,
	}, "", " ")
}

// ---- Download --------------------------------------------------------------------------------

func (s *Server) exportLayout(ctx context.Context, id int64) (*zipLayout, error) {
	var manifest, dataJSON []byte
	var created time.Time
	var zipBytes int64
	if err := s.db.QueryRow(ctx, `SELECT manifest, data_json, created_at, zip_bytes FROM exports WHERE id = $1`, id).
		Scan(&manifest, &dataJSON, &created, &zipBytes); err != nil {
		return nil, err
	}
	rows, err := s.db.Query(ctx, `SELECT path, size, crc32 FROM export_items WHERE export_id = $1 ORDER BY seq`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var objects []zipEntry
	for rows.Next() {
		var e zipEntry
		var crc int64
		if err := rows.Scan(&e.name, &e.size, &crc); err != nil {
			return nil, err
		}
		e.crc = uint32(crc)
		e.file = filepath.Join(s.cfg.DataDir, e.name)
		objects = append(objects, e)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	layout, err := newZipLayout(exportEntries(s.cfg.DataDir, manifest, dataJSON, objects), created)
	if err != nil {
		return nil, err
	}
	if layout.Size() != zipBytes {
		return nil, fmt.Errorf("export %d layout changed: %d != %d bytes", id, layout.Size(), zipBytes)
	}
	return layout, nil
}

func (s *Server) downloadExport(w http.ResponseWriter, r *http.Request) {
	id, ok := exportID(w, r)
	if ok {
		s.serveExport(w, r, id)
	}
}

// downloadExportByLink serves a zip to the phone's system DownloadManager, which can't hold our
// session token safely: the link carries a random token scoped to one export, valid for 30 minutes
// after its last use (each request, including a resumed Range request, extends it).
func (s *Server) downloadExportByLink(w http.ResponseWriter, r *http.Request) {
	var id int64
	err := s.db.QueryRow(r.Context(), `UPDATE exports SET download_token_expires = now() + $2::interval
		WHERE download_token_hash = $1 AND download_token_expires > now() RETURNING id`,
		tokenHash(r.PathValue("token")), fmt.Sprintf("%d seconds", int(downloadLinkTTL.Seconds()))).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		writeError(w, http.StatusNotFound, "LINK_EXPIRED", "this download link has expired; start the download again from the app")
		return
	}
	if err != nil {
		s.internal(w, "export link", err)
		return
	}
	s.serveExport(w, r, id)
}

func (s *Server) serveExport(w http.ResponseWriter, r *http.Request, id int64) {
	info, err := s.loadExport(r.Context(), s.db, id)
	if errors.Is(err, pgx.ErrNoRows) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such export")
		return
	}
	if err != nil {
		s.internal(w, "export", err)
		return
	}
	if info.Status == "ARCHIVED" {
		writeError(w, http.StatusGone, "EXPORT_ARCHIVED", "files of this export were removed from the server; use your saved copy")
		return
	}
	layout, err := s.exportLayout(r.Context(), id)
	if err != nil {
		s.internal(w, "export", err)
		return
	}
	defer layout.Close()
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="`+info.FileName+`"`)
	w.Header().Set("ETag", `"`+info.ManifestSHA256+`"`)
	w.Header().Set("X-Manifest-SHA256", info.ManifestSHA256)
	http.ServeContent(w, r, info.FileName, time.UnixMilli(info.CreatedAt), layout)
}

func (s *Server) exportLink(w http.ResponseWriter, r *http.Request) {
	id, ok := exportID(w, r)
	if !ok {
		return
	}
	admin := userFrom(r)
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		s.internal(w, "export link", err)
		return
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	expires := s.now().Add(downloadLinkTTL)
	tag, err := s.db.Exec(r.Context(), `UPDATE exports SET download_token_hash = $2, download_token_expires = $3 WHERE id = $1 AND status <> 'ARCHIVED'`,
		id, tokenHash(token), expires)
	if err != nil {
		s.internal(w, "export link", err)
		return
	}
	if tag.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such export, or its files were already removed from the server")
		return
	}
	info, err := s.loadExport(r.Context(), s.db, id)
	if err != nil {
		s.internal(w, "export link", err)
		return
	}
	s.auditEvent(r.Context(), "EXPORT_DOWNLOAD_LINK", &admin.ID, admin.deviceID, "EXPORT:"+strconv.FormatInt(id, 10), "")
	writeJSON(w, http.StatusOK, map[string]any{"path": "/v1/export-files/" + token, "expires_at": millis(expires), "export": info})
}

func exportID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		writeError(w, http.StatusBadRequest, "INVALID", "export id must be a number")
		return 0, false
	}
	return id, true
}

// ---- Verified --------------------------------------------------------------------------------

// exportVerified records that the admin's phone re-hashed every zip entry and they all matched the
// manifest, whose own SHA-256 matched ours. From now on phones may clean up these photos' local
// originals (retention), and the server may archive them.
func (s *Server) exportVerified(w http.ResponseWriter, r *http.Request) {
	id, ok := exportID(w, r)
	if !ok {
		return
	}
	var req struct {
		ManifestSHA256  string `json:"manifest_sha256"`
		EntriesVerified int    `json:"entries_verified"`
	}
	if !readJSON(w, r, 4<<10, &req) {
		return
	}
	admin := userFrom(r)
	var problem string
	err := pgx.BeginFunc(r.Context(), s.db, func(tx pgx.Tx) error {
		if err := writeLock(r.Context(), tx); err != nil {
			return err
		}
		var status, manifestSHA string
		var entries int
		err := tx.QueryRow(r.Context(), `SELECT status, manifest_sha256, entry_count FROM exports WHERE id = $1 FOR UPDATE`, id).
			Scan(&status, &manifestSHA, &entries)
		if errors.Is(err, pgx.ErrNoRows) {
			problem = "NOT_FOUND"
			return nil
		}
		if err != nil {
			return err
		}
		if !strings.EqualFold(req.ManifestSHA256, manifestSHA) || req.EntriesVerified != entries {
			problem = "MISMATCH"
			return nil
		}
		if status != "CREATED" {
			return nil // already verified: idempotent
		}
		if _, err := tx.Exec(r.Context(), `UPDATE exports SET status = 'VERIFIED', verified_at = now(), verified_by = $2 WHERE id = $1`, id, admin.ID); err != nil {
			return err
		}
		for _, t := range []struct{ table, kind string }{{"photos", "PHOTO"}, {"reports", "REPORT"}} {
			_, err := tx.Exec(r.Context(), fmt.Sprintf(`
				UPDATE %s SET export_verified_at = now(), change_seq = nextval('change_seq')
				WHERE export_verified_at IS NULL AND id IN (SELECT object_id FROM export_items WHERE export_id = $1 AND kind = $2)`, t.table),
				id, t.kind)
			if err != nil {
				return err
			}
		}
		return nil
	})
	switch {
	case err != nil:
		s.internal(w, "export verified", err)
		return
	case problem == "NOT_FOUND":
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such export")
		return
	case problem == "MISMATCH":
		writeError(w, http.StatusConflict, "MANIFEST_MISMATCH", "the verified manifest or entry count differs from this export; download it again")
		return
	}
	info, err := s.loadExport(r.Context(), s.db, id)
	if err != nil {
		s.internal(w, "export verified", err)
		return
	}
	s.auditEvent(r.Context(), "EXPORT_VERIFIED", &admin.ID, admin.deviceID, "EXPORT:"+strconv.FormatInt(id, 10),
		fmt.Sprintf("entries=%d manifest_sha256=%s", info.EntryCount, info.ManifestSHA256))
	writeJSON(w, http.StatusOK, map[string]any{"export": info})
}

// ---- Archive ---------------------------------------------------------------------------------

// archiveExport removes from the server disk the object files of this (verified) export that
// belong to inspections finalized more than archive_min_age_days ago. Records stay; the objects
// are marked archived and answer 410. Safe to call again: newly old inspections are archived then,
// and files left behind by an interrupted run are removed.
func (s *Server) archiveExport(w http.ResponseWriter, r *http.Request) {
	id, ok := exportID(w, r)
	if !ok {
		return
	}
	ctx := r.Context()
	admin := userFrom(r)
	minAge := s.settingInt(ctx, "archive_min_age_days")
	cutoff := s.now().Add(-time.Duration(minAge) * 24 * time.Hour).UnixMilli()
	type item struct {
		kind, id, path    string
		size              int64
		archived, sameSHA bool
		status            string
		finalizedAt       *int64
	}
	var items []item
	var status string
	counts := map[string]int{}
	err := pgx.BeginFunc(ctx, s.db, func(tx pgx.Tx) error {
		if err := writeLock(ctx, tx); err != nil {
			return err
		}
		err := tx.QueryRow(ctx, `SELECT status FROM exports WHERE id = $1 FOR UPDATE`, id).Scan(&status)
		if err != nil || status == "CREATED" {
			return err
		}
		rows, err := tx.Query(ctx, `
			SELECT ei.kind, ei.object_id::text, ei.path, ei.size, o.archived_at IS NOT NULL, lower(o.sha) = lower(ei.sha256), i.status, i.finalized_at
			FROM export_items ei
			JOIN (SELECT 'PHOTO' AS kind, id, inspection_id, archived_at, sha256 AS sha FROM photos
			      UNION ALL SELECT 'REPORT', id, inspection_id, archived_at, content_hash FROM reports) o
			  ON o.id = ei.object_id AND o.kind = ei.kind
			JOIN inspections i ON i.id = o.inspection_id
			WHERE ei.export_id = $1 ORDER BY ei.seq`, id)
		if err != nil {
			return err
		}
		for rows.Next() {
			var it item
			if err := rows.Scan(&it.kind, &it.id, &it.path, &it.size, &it.archived, &it.sameSHA, &it.status, &it.finalizedAt); err != nil {
				rows.Close()
				return err
			}
			items = append(items, it)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		ids := map[string][]string{}
		for i, it := range items {
			switch {
			case it.archived:
				counts["already_archived"]++
			case !it.sameSHA:
				counts["kept_changed"]++
			case it.status != "FINALIZED" || it.finalizedAt == nil:
				counts["kept_not_finalized"]++
			case *it.finalizedAt >= cutoff:
				counts["kept_too_recent"]++
			default:
				ids[it.kind] = append(ids[it.kind], it.id)
				items[i].archived = true
				counts["archived_now"]++
			}
		}
		for kind, table := range map[string]string{"PHOTO": "photos", "REPORT": "reports"} {
			if len(ids[kind]) == 0 {
				continue
			}
			_, err := tx.Exec(ctx, fmt.Sprintf(`UPDATE %s SET archived_at = now(), archived_export_id = $1, change_seq = nextval('change_seq')
				WHERE id::text = ANY($2) AND archived_at IS NULL`, table), id, ids[kind])
			if err != nil {
				return err
			}
		}
		if counts["archived_now"] > 0 {
			_, err = tx.Exec(ctx, `UPDATE exports SET status = 'ARCHIVED', archived_at = COALESCE(archived_at, now()) WHERE id = $1`, id)
		}
		return err
	})
	if errors.Is(err, pgx.ErrNoRows) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such export")
		return
	}
	if err != nil {
		s.internal(w, "archive", err)
		return
	}
	if status == "CREATED" {
		writeError(w, http.StatusConflict, "NOT_VERIFIED", "download and verify this export on a phone before removing files from the server")
		return
	}
	// Files go only after the records say "archived" (committed above). A crash in between leaves
	// a file that the next call removes; it never leaves a record pointing at a missing file.
	var freed int64
	for _, it := range items {
		if !it.archived {
			continue
		}
		path := filepath.Join(s.cfg.DataDir, it.path)
		if err := os.Remove(path); err == nil {
			freed += it.size
		} else if !errors.Is(err, os.ErrNotExist) {
			s.log.Error("archive remove", "path", it.path, "err", err)
		}
	}
	info, err := s.loadExport(ctx, s.db, id)
	if err != nil {
		s.internal(w, "archive", err)
		return
	}
	s.auditEvent(ctx, "EXPORT_ARCHIVED", &admin.ID, admin.deviceID, "EXPORT:"+strconv.FormatInt(id, 10),
		fmt.Sprintf("archived=%d kept_recent=%d kept_not_finalized=%d freed_bytes=%d min_age_days=%d",
			counts["archived_now"], counts["kept_too_recent"], counts["kept_not_finalized"], freed, minAge))
	writeJSON(w, http.StatusOK, map[string]any{
		"export": info, "archived_now": counts["archived_now"], "already_archived": counts["already_archived"],
		"kept_too_recent": counts["kept_too_recent"], "kept_not_finalized": counts["kept_not_finalized"],
		"kept_changed": counts["kept_changed"], "freed_bytes": freed, "min_age_days": minAge,
	})
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}
