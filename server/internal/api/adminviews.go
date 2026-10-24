package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/jackc/pgx/v5"
)

// Admin views (PRD §3.1): inspections' backup status, phones and their failed sync items, the
// audit log, and the retention policy. Plus the endpoint phones use to report their own queue.

// ---- Settings --------------------------------------------------------------------------------

type settingSpec struct {
	def, min, max int
}

// Retention policy (PRD §5.9, configurable). retention_days: a phone may remove a local original
// this long after the later of finalization and server verification (and only once the photo is
// in a verified export). archive_min_age_days: the server keeps files of finalized inspections at
// least this long before an admin may archive them.
var settingSpecs = map[string]settingSpec{
	"retention_days":       {def: 30, min: 7, max: 365},
	"archive_min_age_days": {def: 60, min: 30, max: 3650},
	// AI requests per user per day (0 = AI off). Caps the bill if a phone is lost or misused.
	"ai_daily_limit": {def: 100, min: 0, max: 1000},
}

func (s *Server) settingInt(ctx context.Context, key string) int {
	spec := settingSpecs[key]
	var v string
	if err := s.db.QueryRow(ctx, `SELECT value FROM settings WHERE key = $1`, key).Scan(&v); err != nil {
		return spec.def
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < spec.min || n > spec.max {
		return spec.def
	}
	return n
}

func (s *Server) settingsBody(ctx context.Context) map[string]any {
	retention := s.settingInt(ctx, "retention_days")
	return map[string]any{
		"retention_days":       retention,
		"archive_min_age_days": s.settingInt(ctx, "archive_min_age_days"),
		"export_warn_bytes":    int64(exportWarnBytes),
		"policy_version":       fmt.Sprintf("retention-%dd", retention),
		"ai_enabled":           s.aiEnabled(ctx),
		"ai_daily_limit":       s.settingInt(ctx, "ai_daily_limit"),
	}
}

func (s *Server) getSettings(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.settingsBody(r.Context()))
}

func (s *Server) updateSettings(w http.ResponseWriter, r *http.Request) {
	var req map[string]int
	if !readJSON(w, r, 4<<10, &req) {
		return
	}
	for k, v := range req {
		spec, ok := settingSpecs[k]
		if !ok {
			writeError(w, http.StatusBadRequest, "INVALID", "unknown setting "+k)
			return
		}
		if v < spec.min || v > spec.max {
			writeError(w, http.StatusBadRequest, "INVALID", fmt.Sprintf("%s must be between %d and %d", k, spec.min, spec.max))
			return
		}
	}
	admin := userFrom(r)
	var changes []string
	for k, v := range req {
		_, err := s.db.Exec(r.Context(), `INSERT INTO settings (key, value, updated_by) VALUES ($1, $2, $3)
			ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()`,
			k, strconv.Itoa(v), admin.ID)
		if err != nil {
			s.internal(w, "settings", err)
			return
		}
		changes = append(changes, fmt.Sprintf("%s=%d", k, v))
	}
	if len(changes) > 0 {
		s.auditEvent(r.Context(), "SETTINGS_CHANGED", &admin.ID, admin.deviceID, "", strings.Join(changes, " "))
	}
	writeJSON(w, http.StatusOK, s.settingsBody(r.Context()))
}

// ---- Inspections' backup status --------------------------------------------------------------

func (s *Server) adminInspections(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	rows, err := s.db.Query(r.Context(), `
		SELECT i.id::text, i.client_name, i.property_address, i.inspection_date, i.status, i.finalized_at,
		       COALESCE(u.display_name, ''), (extract(epoch FROM i.server_updated_at) * 1000)::bigint,
		       p.total, p.verified, p.exported, p.archived, r.total, r.verified
		FROM inspections i
		LEFT JOIN users u ON u.id::text = i.inspector_user_id
		CROSS JOIN LATERAL (SELECT count(*) AS total, count(verified_at) AS verified, count(export_verified_at) AS exported,
		                           count(archived_at) AS archived
		                    FROM photos WHERE inspection_id = i.id AND deleted_at IS NULL) p
		CROSS JOIN LATERAL (SELECT count(*) AS total, count(verified_at) AS verified FROM reports WHERE inspection_id = i.id) r
		WHERE i.deleted_at IS NULL
		  AND ($1::text = '' OR i.client_name ILIKE '%' || $1::text || '%' OR i.property_address ILIKE '%' || $1::text || '%')
		ORDER BY i.server_updated_at DESC LIMIT 300`, q)
	if err != nil {
		s.internal(w, "admin inspections", err)
		return
	}
	defer rows.Close()
	type row struct {
		ID              string `json:"id"`
		ClientName      string `json:"client_name"`
		Address         string `json:"property_address"`
		Date            string `json:"inspection_date"`
		Status          string `json:"status"`
		FinalizedAt     *int64 `json:"finalized_at"`
		Inspector       string `json:"inspector"`
		ServerUpdatedAt int64  `json:"server_updated_at"`
		Photos          int64  `json:"photos"`
		PhotosVerified  int64  `json:"photos_verified"`
		PhotosExported  int64  `json:"photos_exported"`
		PhotosArchived  int64  `json:"photos_archived"`
		Reports         int64  `json:"reports"`
		ReportsVerified int64  `json:"reports_verified"`
	}
	out := []row{}
	for rows.Next() {
		var x row
		if err := rows.Scan(&x.ID, &x.ClientName, &x.Address, &x.Date, &x.Status, &x.FinalizedAt, &x.Inspector, &x.ServerUpdatedAt,
			&x.Photos, &x.PhotosVerified, &x.PhotosExported, &x.PhotosArchived, &x.Reports, &x.ReportsVerified); err != nil {
			s.internal(w, "admin inspections", err)
			return
		}
		out = append(out, x)
	}
	writeJSON(w, http.StatusOK, map[string]any{"inspections": out})
}

// ---- Devices and their failed sync items -----------------------------------------------------

func (s *Server) adminDevices(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `
		SELECT d.id::text, u.display_name, u.employee_code, d.device_label, d.platform, d.app_version,
		       (extract(epoch FROM d.last_seen_at) * 1000)::bigint, (extract(epoch FROM d.revoked_at) * 1000)::bigint,
		       d.sync_report, (extract(epoch FROM d.sync_reported_at) * 1000)::bigint
		FROM devices d JOIN users u ON u.id = d.user_id
		ORDER BY d.last_seen_at DESC LIMIT 200`)
	if err != nil {
		s.internal(w, "admin devices", err)
		return
	}
	defer rows.Close()
	type row struct {
		ID         string          `json:"id"`
		User       string          `json:"user"`
		Code       string          `json:"employee_code"`
		Label      string          `json:"label"`
		Platform   string          `json:"platform"`
		AppVersion string          `json:"app_version"`
		LastSeenAt int64           `json:"last_seen_at"`
		RevokedAt  *int64          `json:"revoked_at"`
		Report     json.RawMessage `json:"sync_report"`
		ReportedAt *int64          `json:"sync_reported_at"`
	}
	out := []row{}
	for rows.Next() {
		var x row
		var report []byte
		if err := rows.Scan(&x.ID, &x.User, &x.Code, &x.Label, &x.Platform, &x.AppVersion, &x.LastSeenAt, &x.RevokedAt, &report, &x.ReportedAt); err != nil {
			s.internal(w, "admin devices", err)
			return
		}
		if len(report) > 0 {
			x.Report = report
		}
		out = append(out, x)
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": out})
}

// updateDevice blocks or unblocks a phone (PRD §5.1 P1). Blocking ends its sessions at once.
func (s *Server) updateDevice(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var req struct {
		Revoked *bool `json:"revoked"`
	}
	if !readJSON(w, r, 1<<10, &req) {
		return
	}
	if !isUUID(id) || req.Revoked == nil {
		writeError(w, http.StatusBadRequest, "INVALID", "a device UUID and revoked (true/false) are required")
		return
	}
	admin := userFrom(r)
	if *req.Revoked && admin.deviceID != nil && *admin.deviceID == id {
		writeError(w, http.StatusBadRequest, "SELF_LOCKOUT", "you cannot block the phone you are using")
		return
	}
	var found bool
	err := pgx.BeginFunc(r.Context(), s.db, func(tx pgx.Tx) error {
		tag, err := tx.Exec(r.Context(), `UPDATE devices SET revoked_at = CASE WHEN $2 THEN COALESCE(revoked_at, now()) ELSE NULL END WHERE id = $1`, id, *req.Revoked)
		if err != nil {
			return err
		}
		found = tag.RowsAffected() == 1
		if found && *req.Revoked {
			_, err = tx.Exec(r.Context(), `UPDATE sessions SET revoked_at = now() WHERE device_id = $1 AND revoked_at IS NULL`, id)
		}
		return err
	})
	if err != nil {
		s.internal(w, "update device", err)
		return
	}
	if !found {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such device")
		return
	}
	typ := "DEVICE_UNBLOCKED"
	if *req.Revoked {
		typ = "DEVICE_BLOCKED"
	}
	s.auditEvent(r.Context(), typ, &admin.ID, admin.deviceID, "DEVICE:"+id, "")
	w.WriteHeader(http.StatusNoContent)
}

// deviceReport: after each sync a phone reports what is still queued and what keeps failing, and
// uploads its own audit events (e.g. retention cleanup) once. Admins see this in the devices view.
func (s *Server) deviceReport(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	if u.deviceID == nil {
		writeError(w, http.StatusBadRequest, "NO_DEVICE", "this session has no registered device")
		return
	}
	type problem struct {
		Type     string `json:"type"`
		ID       string `json:"id"`
		Op       string `json:"op"`
		Code     string `json:"code"`
		Message  string `json:"message"`
		Attempts int    `json:"attempts"`
	}
	type event struct {
		ID         string `json:"id"`
		Type       string `json:"type"`
		EntityType string `json:"entity_type"`
		EntityID   string `json:"entity_id"`
		Details    string `json:"details"`
		CreatedAt  int64  `json:"created_at"`
	}
	var req struct {
		AppVersion          string    `json:"app_version"`
		PendingRecords      int       `json:"pending_records"`
		PendingPhotos       int       `json:"pending_photos"`
		FreeBytes           int64     `json:"free_bytes"`
		LocalOriginals      int       `json:"local_originals"`
		LocalOriginalsBytes int64     `json:"local_originals_bytes"`
		CleanupBlocked      int       `json:"cleanup_blocked"`
		Problems            []problem `json:"problems"`
		Audit               []event   `json:"audit"`
	}
	if !readJSON(w, r, 256<<10, &req) {
		return
	}
	if len(req.Problems) > 50 {
		req.Problems = req.Problems[:50]
	}
	if len(req.Audit) > 200 {
		writeError(w, http.StatusRequestEntityTooLarge, "TOO_MANY_ITEMS", "send at most 200 audit events per report")
		return
	}
	for i := range req.Problems {
		p := &req.Problems[i]
		p.Type, p.ID, p.Op, p.Code, p.Message = trim(p.Type, 20), trim(p.ID, 40), trim(p.Op, 20), trim(p.Code, 40), trim(p.Message, 300)
	}
	audit := req.Audit
	req.Audit = nil
	req.AppVersion = trim(req.AppVersion, 50)
	report, _ := json.Marshal(req)
	ctx := r.Context()
	if _, err := s.db.Exec(ctx, `UPDATE devices SET sync_report = $2, sync_reported_at = now(),
		app_version = COALESCE(NULLIF($3, ''), app_version) WHERE id = $1`, *u.deviceID, report, req.AppVersion); err != nil {
		s.internal(w, "device report", err)
		return
	}
	accepted := []string{}
	for _, e := range audit {
		if !isUUID(e.ID) || !validEventType(e.Type) {
			continue
		}
		entity := ""
		if e.EntityType != "" {
			entity = trim(e.EntityType, 20) + ":" + trim(e.EntityID, 40)
		}
		details := strings.TrimSpace(fmt.Sprintf("device_time=%d %s", e.CreatedAt, trim(e.Details, 1000)))
		_, err := s.db.Exec(ctx, `INSERT INTO audit_events (type, user_id, device_id, entity, details, client_event_id)
			VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT (client_event_id) DO NOTHING`,
			e.Type, u.ID, *u.deviceID, entity, details, e.ID)
		if err != nil {
			s.internal(w, "device report", err)
			return
		}
		accepted = append(accepted, e.ID) // a duplicate was stored earlier: also done
	}
	writeJSON(w, http.StatusOK, map[string]any{"accepted": accepted})
}

func validEventType(t string) bool {
	if t == "" || len(t) > 40 {
		return false
	}
	for _, c := range t {
		if (c < 'A' || c > 'Z') && c != '_' && (c < '0' || c > '9') {
			return false
		}
	}
	return true
}

// ---- Audit log -------------------------------------------------------------------------------

func (s *Server) adminAudit(w http.ResponseWriter, r *http.Request) {
	before, _ := strconv.ParseInt(r.URL.Query().Get("before"), 10, 64)
	limit, err := strconv.Atoi(r.URL.Query().Get("limit"))
	if err != nil || limit <= 0 || limit > 200 {
		limit = 100
	}
	typ := strings.TrimSpace(r.URL.Query().Get("type"))
	rows, err := s.db.Query(r.Context(), `
		SELECT a.id, a.type, COALESCE(u.display_name, ''), COALESCE(u.employee_code, ''), COALESCE(d.device_label, ''),
		       COALESCE(a.entity, ''), COALESCE(a.details, ''), (extract(epoch FROM a.created_at) * 1000)::bigint,
		       a.client_event_id IS NOT NULL
		FROM audit_events a
		LEFT JOIN users u ON u.id = a.user_id
		LEFT JOIN devices d ON d.id = a.device_id
		WHERE ($1::bigint = 0 OR a.id < $1::bigint) AND ($2::text = '' OR a.type = $2::text)
		ORDER BY a.id DESC LIMIT $3`, before, typ, limit)
	if err != nil {
		s.internal(w, "audit", err)
		return
	}
	defer rows.Close()
	type row struct {
		ID         int64  `json:"id"`
		Type       string `json:"type"`
		User       string `json:"user"`
		Code       string `json:"employee_code"`
		Device     string `json:"device"`
		Entity     string `json:"entity"`
		Details    string `json:"details"`
		CreatedAt  int64  `json:"created_at"`
		FromDevice bool   `json:"from_device"`
	}
	out := []row{}
	for rows.Next() {
		var x row
		if err := rows.Scan(&x.ID, &x.Type, &x.User, &x.Code, &x.Device, &x.Entity, &x.Details, &x.CreatedAt, &x.FromDevice); err != nil {
			s.internal(w, "audit", err)
			return
		}
		out = append(out, x)
	}
	if err := rows.Err(); err != nil && !errors.Is(err, context.Canceled) {
		s.internal(w, "audit", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"events": out})
}
