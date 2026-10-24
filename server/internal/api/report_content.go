package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"

	"github.com/jackc/pgx/v5"
)

// Report content snapshots. When a phone finalizes a version it also uploads exactly what that
// version's PDF showed (the same texts, the same photos in the same order). The Word copy of any
// version is built from it, so it always matches its PDF — even after the inspection is reopened,
// or when some later edit never reached the server. Versions finalized before 0.9.0 have none;
// their Word copy falls back to the live data (latest finalized version only).

const maxReportContent = 4 << 20

type reportContent struct {
	Format          int                 `json:"format"` // 1
	ReportID        string              `json:"report_id"`
	InspectionID    string              `json:"inspection_id"`
	Version         int64               `json:"version"`
	ReportNumber    string              `json:"report_number"`
	ClientName      string              `json:"client_name"`
	ClientPhone     string              `json:"client_phone"`
	PropertyAddress string              `json:"property_address"`
	UnitNumber      string              `json:"unit_number"`
	PropertyType    string              `json:"property_type"`
	InspectionDate  string              `json:"inspection_date"` // YYYY-MM-DD
	InspectorName   string              `json:"inspector_name"`
	Summary         string              `json:"summary"`
	Rooms           []reportContentRoom `json:"rooms"`
}

type reportContentRoom struct {
	Name     string                 `json:"name"`
	Photos   []reportContentPhoto   `json:"photos"` // the room's common photos, printed before its findings
	Findings []reportContentFinding `json:"findings"`
}

type reportContentFinding struct {
	Title          string               `json:"title"` // as printed: an untitled finding already carries its category
	Category       string               `json:"category"`
	Severity       string               `json:"severity"`
	Status         string               `json:"status"`
	Observation    string               `json:"observation"`
	Implication    string               `json:"implication"`
	Recommendation string               `json:"recommendation"`
	Photos         []reportContentPhoto `json:"photos"`
}

type reportContentPhoto struct {
	ID      string `json:"id"`
	SHA256  string `json:"sha256"`
	Caption string `json:"caption"`
}

// PUT /v1/reports/{id}/content — the finalizing phone's snapshot of a version. Stored once; a
// retry with the same bytes is a no-op, different bytes are refused (a version never changes).
func (s *Server) putReportContent(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !isUUID(id) {
		writeError(w, http.StatusBadRequest, "INVALID", "id must be a UUID")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxReportContent))
	if err != nil {
		writeError(w, http.StatusRequestEntityTooLarge, "TOO_LARGE", "report content is too large")
		return
	}
	var c reportContent
	dec := json.NewDecoder(bytes.NewReader(body))
	if err := dec.Decode(&c); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_JSON", "invalid JSON body")
		return
	}
	if msg := c.validate(id); msg != "" {
		writeError(w, http.StatusBadRequest, "INVALID", msg)
		return
	}
	sum := sha256.Sum256(body)
	hash := hex.EncodeToString(sum[:])
	var inspection, by string
	var version int64
	var stored *string
	err = s.db.QueryRow(r.Context(), `SELECT inspection_id::text, version, content_sha256, updated_by::text FROM reports WHERE id = $1`, id).
		Scan(&inspection, &version, &stored, &by)
	if errors.Is(err, pgx.ErrNoRows) {
		// The report's metadata hasn't been pushed yet: the phone tries again at its next sync.
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such report yet")
		return
	}
	if err != nil {
		s.internal(w, "report content", err)
		return
	}
	// Only whoever pushed the version (the finalizing phone's user), or an admin.
	if u := userFrom(r); u.ID != by && u.Role != "ADMIN" {
		writeError(w, http.StatusForbidden, "FORBIDDEN", "only the phone that finalized this version uploads its content")
		return
	}
	if inspection != c.InspectionID || version != c.Version {
		writeError(w, http.StatusBadRequest, "INVALID", "inspection_id or version doesn't match the report")
		return
	}
	switch {
	case stored != nil && *stored == hash:
	case stored != nil:
		writeError(w, http.StatusConflict, "CONTENT_EXISTS", "this version's content is already stored")
		return
	default:
		if _, err := s.db.Exec(r.Context(), `UPDATE reports SET content_json = $2, content_sha256 = $3, content_at = now()
			WHERE id = $1 AND content_sha256 IS NULL`, id, string(body), hash); err != nil {
			s.internal(w, "report content", err)
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"content_sha256": hash})
}

func (c *reportContent) validate(id string) string {
	switch {
	case c.Format != 1:
		return "format must be 1"
	case c.ReportID != id:
		return "report_id doesn't match the URL"
	case !isUUID(c.InspectionID):
		return "inspection_id must be a UUID"
	case c.Version < 1:
		return "version must be 1 or more"
	}
	if _, ok := parseDay(c.InspectionDate); !ok {
		return "inspection_date must be YYYY-MM-DD"
	}
	for _, room := range c.Rooms {
		for _, p := range room.Photos {
			if !isUUID(p.ID) {
				return "photo ids must be UUIDs"
			}
		}
		for _, f := range room.Findings {
			for _, p := range f.Photos {
				if !isUUID(p.ID) {
					return "photo ids must be UUIDs"
				}
			}
		}
	}
	return ""
}
