package api

import (
	"crypto/rand"
	"embed"
	"encoding/base64"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"path"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// Web dashboard (served at /web/ by this same server; the /v1 API is unchanged for phones).
// Employees: list of reports, download PDF or Word. Admins: also the attendance calendar, leave,
// comp-offs and holidays. Plain HTML + JS embedded in the binary: nothing else to host.
//
// Sign-in uses the same employee code, password, rate gate and lockouts as the app. The session is
// an HttpOnly, Secure, SameSite=Strict cookie (JS can't read it; other sites can't send it), kept in
// the same sessions table with web = true. Requests that change something must also carry
// X-Home-Inspection-Web: 1, which a cross-site form can't add. Web sessions end after 12 idle hours.

// hsts tells browsers to use HTTPS only for this host for a year (Caddy serves HTTPS only anyway).
const hsts = "max-age=31536000"

//go:embed web
var webFS embed.FS

const (
	// __Host-: the browser only accepts it Secure, for this exact host, on Path=/ (no subdomain can set it).
	webCookie     = "__Host-home_inspection_web"
	webSessionTTL = 12 * time.Hour
)

func webToken(r *http.Request) (string, bool) {
	c, err := r.Cookie(webCookie)
	if err != nil || c.Value == "" {
		return "", false
	}
	return c.Value, true
}

func webRequestAllowed(r *http.Request) bool {
	return r.Method == http.MethodGet || r.Method == http.MethodHead || r.Header.Get("X-Home-Inspection-Web") == "1"
}

// webRoutes are the only API routes a web session may use: what the dashboard needs. Phone-only
// routes (sync, uploads, attendance, tracking, face, AI, exports, user admin) refuse it, so a
// browser session can never act as a phone.
var webRoutes = []struct {
	method, path string
	prefix       bool
	suffix       string // with prefix: the path must also end with this (e.g. a selfie under /v1/attendance/{id})
}{
	{"GET", "/v1/me", false, ""}, {"POST", "/v1/auth/logout", false, ""}, {"POST", "/v1/auth/password", false, ""},
	{"GET", "/v1/reports", false, ""}, {"GET", "/v1/reports/", true, ""}, // PDF and Word downloads
	{"GET", "/v1/calendar", false, ""}, {"GET", "/v1/leave", false, ""}, {"GET", "/v1/holidays", false, ""},
	{"GET", "/v1/admin/calendar", true, ""}, {"GET", "/v1/admin/leave", false, ""}, {"POST", "/v1/admin/leave", true, ""},
	{"PUT", "/v1/admin/holidays/", true, ""}, {"DELETE", "/v1/admin/holidays/", true, ""}, {"POST", "/v1/admin/comp-offs", false, ""},
	// Admin pages brought from the app (2026-10-21): attendance by day, employees, backup status, phones, audit, exports.
	{"GET", "/v1/admin/attendance", false, ""},
	{"GET", "/v1/attendance/", true, "/selfie"}, {"GET", "/v1/face/enrollments/", true, "/photo"},
	{"GET", "/v1/admin/users", false, ""}, {"POST", "/v1/admin/users", false, ""}, {"PATCH", "/v1/admin/users/", true, ""},
	{"GET", "/v1/admin/inspections", false, ""},
	{"GET", "/v1/admin/devices", false, ""}, {"PATCH", "/v1/admin/devices/", true, ""},
	{"GET", "/v1/admin/audit", false, ""},
	{"GET", "/v1/admin/storage", false, ""}, {"POST", "/v1/admin/exports", false, ""},
	{"POST", "/v1/admin/exports/", true, "/link"}, {"POST", "/v1/admin/exports/", true, "/verified"}, {"POST", "/v1/admin/exports/", true, "/archive"},
}

func webRouteAllowed(r *http.Request) bool {
	for _, rt := range webRoutes {
		if r.Method != rt.method && !(r.Method == http.MethodHead && rt.method == http.MethodGet) {
			continue
		}
		if r.URL.Path == rt.path || (rt.prefix && strings.HasPrefix(r.URL.Path, rt.path) && strings.HasSuffix(r.URL.Path, rt.suffix)) {
			return true
		}
	}
	return false
}

func setWebCookie(w http.ResponseWriter, token string) {
	http.SetCookie(w, &http.Cookie{Name: webCookie, Value: token, Path: "/", HttpOnly: true, Secure: true, SameSite: http.SameSiteStrictMode})
}

func clearWebCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{Name: webCookie, Value: "", Path: "/", MaxAge: -1, HttpOnly: true, Secure: true, SameSite: http.SameSiteStrictMode})
}

// POST /v1/auth/web-login {employee_code, password} → sets the cookie, returns {user}.
func (s *Server) webLogin(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Home-Inspection-Web") != "1" {
		writeError(w, http.StatusForbidden, "FORBIDDEN", "missing X-Home-Inspection-Web header")
		return
	}
	var req struct {
		EmployeeCode string `json:"employee_code"`
		Password     string `json:"password"`
	}
	if !readJSON(w, r, 4<<10, &req) {
		return
	}
	u, ok := s.checkCredentials(w, r, req.EmployeeCode, req.Password, "")
	if !ok {
		return
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		s.internal(w, "web login", err)
		return
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	if _, err := s.db.Exec(r.Context(), `INSERT INTO sessions (token_hash, user_id, expires_at, web) VALUES ($1, $2, $3, true)`,
		tokenHash(token), u.ID, s.now().Add(webSessionTTL)); err != nil {
		s.internal(w, "web login", err)
		return
	}
	s.auditEvent(r.Context(), "LOGIN", &u.ID, nil, "", "web ip="+clientIP(r))
	w.Header().Set("Strict-Transport-Security", hsts)
	setWebCookie(w, token)
	writeJSON(w, http.StatusOK, map[string]any{"user": u, "server_time": millis(s.now())})
}

// ---- Static files ------------------------------------------------------------------------------

func (s *Server) webStatic(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/web/")
	if name == "" {
		name = "index.html"
	}
	name = path.Clean(name)
	data, err := fs.ReadFile(webFS, "web/"+name)
	if err != nil || strings.Contains(name, "..") {
		http.NotFound(w, r)
		return
	}
	types := map[string]string{".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
		".css": "text/css; charset=utf-8", ".jpg": "image/jpeg", ".svg": "image/svg+xml"}
	ct := types[path.Ext(name)]
	if ct == "" {
		http.NotFound(w, r)
		return
	}
	h := w.Header()
	h.Set("Content-Type", ct)
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("X-Frame-Options", "DENY")
	h.Set("Strict-Transport-Security", hsts)
	h.Set("Content-Security-Policy", "default-src 'self'; img-src 'self' blob: data:; style-src 'self'; script-src 'self'; "+
		"connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
	h.Set("Cache-Control", "no-cache") // revalidate, so a deploy shows at once
	_, _ = w.Write(data)
}

// ---- Reports list (any signed-in user) -----------------------------------------------------------

const reportsPage = 200

// GET /v1/reports?q=&before= — inspections with their report versions, newest first, a page at a
// time: next is the before= of the following page ("" after the last). Everyone sees all
// inspections (decision: same as the app).
func (s *Server) listReports(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if len(q) > 100 {
		q = q[:100]
	}
	// Cursor: inspection_date|created_at|id of the last row of the previous page.
	var bDate, bID string
	var bCreated int64
	if before := r.URL.Query().Get("before"); before != "" {
		parts := strings.Split(before, "|")
		var err error
		if len(parts) == 3 {
			bCreated, err = strconv.ParseInt(parts[1], 10, 64)
		}
		if _, ok := parseDay(safeIndex(parts, 0)); len(parts) != 3 || err != nil || !ok || !isUUID(parts[2]) {
			writeError(w, http.StatusBadRequest, "INVALID", "bad before cursor")
			return
		}
		bDate, bID = parts[0], parts[2]
	}
	rows, err := s.db.Query(r.Context(), `
		SELECT i.id::text, i.client_name, i.property_address, COALESCE(i.unit_number, ''), i.inspection_date, i.status, i.report_version,
		       COALESCE(u.display_name, ''), i.created_at
		FROM inspections i
		LEFT JOIN users u ON u.id::text = i.inspector_user_id
		WHERE i.deleted_at IS NULL
		  AND ($1 = '' OR i.client_name ILIKE '%' || $1 || '%' OR i.property_address ILIKE '%' || $1 || '%'
		       OR COALESCE(u.display_name, '') ILIKE '%' || $1 || '%')
		  AND ($2 = '' OR (i.inspection_date, i.created_at, i.id::text) < ($2, $3::bigint, $4))
		ORDER BY i.inspection_date DESC, i.created_at DESC, i.id::text DESC LIMIT $5`, q, bDate, bCreated, bID, reportsPage)
	if err != nil {
		s.internal(w, "reports", err)
		return
	}
	defer rows.Close()
	type report struct {
		ID            string `json:"id"`
		Version       int64  `json:"version"`
		FinalizedAt   *int64 `json:"finalized_at"`
		PDF           string `json:"pdf"`  // AVAILABLE | UPLOADING | ARCHIVED
		DocxAvailable bool   `json:"docx"` // a Word copy can be built (see docxReason)
		DocxReason    string `json:"docx_reason,omitempty"`
	}
	type item struct {
		ID        string   `json:"id"`
		Number    string   `json:"report_number"`
		Client    string   `json:"client_name"`
		Address   string   `json:"property_address"`
		Date      string   `json:"inspection_date"`
		Status    string   `json:"status"`
		Inspector string   `json:"inspector"`
		Report    *report  `json:"report"`   // the latest version
		Versions  []report `json:"versions"` // every version, newest first
		current   int64
	}
	out := []item{}
	next := ""
	for rows.Next() {
		var it item
		var unit string
		var created int64
		if err := rows.Scan(&it.ID, &it.Client, &it.Address, &unit, &it.Date, &it.Status, &it.current, &it.Inspector, &created); err != nil {
			s.internal(w, "reports", err)
			return
		}
		if unit != "" {
			it.Address = unit + ", " + it.Address
		}
		it.Number = reportNumber(it.Date, it.ID)
		it.Versions = []report{}
		out = append(out, it)
		next = fmt.Sprintf("%s|%d|%s", it.Date, created, it.ID)
	}
	if err := rows.Err(); err != nil {
		s.internal(w, "reports", err)
		return
	}
	rows.Close()
	if len(out) < reportsPage {
		next = ""
	}
	byID := map[string]*item{}
	ids := make([]string, 0, len(out))
	for i := range out {
		byID[out[i].ID] = &out[i]
		ids = append(ids, out[i].ID)
	}
	vrows, err := s.db.Query(r.Context(), `SELECT inspection_id::text, id::text, version, finalized_at, verified_at IS NOT NULL, archived_at IS NOT NULL,
		content_sha256 IS NOT NULL
		FROM reports WHERE inspection_id::text = ANY($1) ORDER BY inspection_id, version DESC`, ids)
	if err != nil {
		s.internal(w, "reports", err)
		return
	}
	defer vrows.Close()
	for vrows.Next() {
		var insp string
		var v report
		var verified, archived, content bool
		if err := vrows.Scan(&insp, &v.ID, &v.Version, &v.FinalizedAt, &verified, &archived, &content); err != nil {
			s.internal(w, "reports", err)
			return
		}
		it := byID[insp]
		v.PDF = "UPLOADING"
		switch {
		case archived:
			v.PDF = "ARCHIVED"
		case verified:
			v.PDF = "AVAILABLE"
		}
		v.DocxAvailable, v.DocxReason = docxReason(content, it.Status, it.current, v.Version)
		it.Versions = append(it.Versions, v)
	}
	if err := vrows.Err(); err != nil {
		s.internal(w, "reports", err)
		return
	}
	for i := range out {
		if len(out[i].Versions) > 0 {
			out[i].Report = &out[i].Versions[0]
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"inspections": out, "next": next})
}

// docxReason: whether a version's Word copy can be built, and if not, why (shown on the web).
func docxReason(hasContent bool, status string, current, version int64) (bool, string) {
	switch {
	case hasContent:
		return true, ""
	case status == "FINALIZED" && current == version:
		return true, "" // built from the live data (finalized before Word copies were kept)
	case current == version:
		return false, "Reopened for editing on a phone. Finalize it again for a Word copy of the new version."
	default:
		return false, "Finalized before Word copies were kept: only the PDF of this version exists."
	}
}

func safeIndex(parts []string, i int) string {
	if i < len(parts) {
		return parts[i]
	}
	return ""
}

// reportFileName names a report download like the phone does: HomeInspection-Report-<number>-v<version>.
func (s *Server) reportFileName(r *http.Request, reportID, ext string) string {
	var date, inspection string
	var version int64
	err := s.db.QueryRow(r.Context(), `SELECT i.inspection_date, i.id::text, r.version FROM reports r JOIN inspections i ON i.id = r.inspection_id WHERE r.id = $1`,
		reportID).Scan(&date, &inspection, &version)
	if errors.Is(err, pgx.ErrNoRows) || err != nil {
		return "HomeInspection-Report." + ext
	}
	return fmt.Sprintf("HomeInspection-Report-%s-v%d.%s", reportNumber(date, inspection), version, ext)
}
