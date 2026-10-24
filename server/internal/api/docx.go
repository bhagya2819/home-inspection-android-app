package api

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// Word copy of a report (web dashboard). The PDF is made on the phone; this editable copy is made
// here from the synced data and photos, following the PDF layout (core/report/ReportLayout.kt):
// cover band, "Prepared for", summary with severity counts, room-by-room findings with a two-column
// photo grid, scope and limitations. Built from the version's content snapshot (report_content.go),
// so it matches its PDF; versions without one (before 0.9.0) only for the latest version of a
// FINALIZED inspection, from the live data.
//
// A .docx is a zip of XML parts, so this needs nothing beyond the standard library. Built files
// are cached under DATA_DIR/cache/docx, keyed by the report and a fingerprint of everything it
// shows; entries unused for a week are removed.

//go:embed web/logo.jpg
var logoJPEG []byte

const (
	docxCacheDir = "cache/docx"
	docxCacheAge = 7 * 24 * time.Hour
)

// docxBuild allows one build at a time: decoding photos is the memory peak (~30 MB each).
var docxBuild = make(chan struct{}, 1)

var severityLabel = map[string]string{"INFO": "Observation", "MINOR": "Low", "MODERATE": "Moderate", "MAJOR": "High", "URGENT": "Urgent"}
var severityOrder = []string{"URGENT", "MAJOR", "MODERATE", "MINOR", "INFO"}

// Same pairs as the PDF's severity chips: background, text.
var severityColors = map[string][2]string{
	"INFO": {"E3ECF7", "1F4E79"}, "MINOR": {"DDE7D8", "1B392B"}, "MODERATE": {"FFF3D8", "7A4B00"},
	"MAJOR": {"FFE0CC", "8A3300"}, "URGENT": {"FFDAD6", "93000A"},
}

var findingStatusLabel = map[string]string{"NOT_APPLICABLE": "Not applicable", "RESOLVED": "Resolved"}

const (
	cGreen = "26552A"
	cFresh = "4F8F48"
	cDeep  = "1B392B"
	cCream = "F8F6EA"
	cSage  = "DDE7D8"
	cText  = "202A22"
	cMuted = "6D776E"
	cLine  = "C9D0C5"
)

// Scope text: the same paragraphs as ReportLayout.ScopeText.
var scopeText = []string{
	"This report records the visible condition of the accessible parts of the property on the inspection date. It is a non-invasive visual inspection, not a structural engineering survey, a valuation, or a guarantee of future performance.",
	"Areas that were concealed, locked, furnished, covered or otherwise not accessible were not inspected. Defects may exist that were not visible at the time of inspection.",
	"Severity ratings are the inspector's professional opinion. Urgent: needs immediate attention. High: significant, repair soon. Moderate: plan a repair. Low: minor. Observation: noted for information.",
	"Recommended work should be carried out by suitably qualified professionals. Where the report suggests specialist advice, obtain it before relying on this report for decisions.",
	"This report is prepared for the client named above and should be read in full.",
}

type docxPhoto struct {
	id, sha, key, caption string
	archived              bool
}

type docxFinding struct {
	title, category, severity, status, observation, implication, recommendation string
	photos                                                                      []docxPhoto
}

type docxRoom struct {
	name     string
	photos   []docxPhoto // the room's common photos (findings of kind ROOM_PHOTOS), before its findings
	findings []docxFinding
}

// allPhotos in print order: the room's common photos, then each finding's (the PDF numbers them so).
func (r docxRoom) allPhotos() []docxPhoto {
	out := append([]docxPhoto{}, r.photos...)
	for _, f := range r.findings {
		out = append(out, f.photos...)
	}
	return out
}

type docxReport struct {
	reportID, inspectionID, number                                   string
	version                                                          int64
	client, phone, address, unit, propType, date, inspector, summary string
	rooms                                                            []docxRoom
	fingerprint                                                      string
}

var errStaleVersion = errors.New("stale version")
var errPhotosUploading = errors.New("photos uploading")

// docxLayout changes whenever the Word layout does, so cached files of the old layout aren't served.
const docxLayout = "2"

func (s *Server) loadDocxReport(ctx context.Context, reportID string) (*docxReport, error) {
	d := &docxReport{reportID: reportID}
	var status, date string
	var current int64
	var unit, propType, summary, content, contentSHA *string
	var updated time.Time
	err := s.db.QueryRow(ctx, `
		SELECT r.version, i.id::text, i.status, i.report_version, i.client_name, i.client_phone, i.property_address, i.unit_number,
		       i.property_type, i.inspection_date, COALESCE(u.display_name, ''), i.summary, i.server_updated_at, r.content_json, r.content_sha256
		FROM reports r JOIN inspections i ON i.id = r.inspection_id
		LEFT JOIN users u ON u.id::text = i.inspector_user_id
		WHERE r.id = $1 AND i.deleted_at IS NULL`, reportID).
		Scan(&d.version, &d.inspectionID, &status, &current, &d.client, &d.phone, &d.address, &unit, &propType, &date, &d.inspector, &summary,
			&updated, &content, &contentSHA)
	if err != nil {
		return nil, err
	}
	if content != nil {
		return s.docxFromContent(ctx, d, *content, *contentSHA)
	}
	// No snapshot (finalized before 0.9.0): only the latest version of a finalized inspection can be
	// rebuilt from the live data.
	if status != "FINALIZED" || current != d.version {
		return nil, errStaleVersion
	}
	d.unit, d.propType, d.summary = deref(unit), deref(propType), deref(summary)
	d.number = reportNumber(date, d.inspectionID)
	d.date = longDate(date)
	fp := sha256.New()
	fmt.Fprintf(fp, "live%s|%s|%d|%d|%s|", docxLayout, reportID, d.version, updated.UnixNano(), d.inspector)

	rows, err := s.db.Query(ctx, `
		SELECT r.id::text, r.name, f.id::text, COALESCE(f.title, ''), COALESCE(f.category, ''), COALESCE(f.severity, ''), f.status,
		       COALESCE(f.detailed_observation, ''), COALESCE(f.implication, ''), COALESCE(f.recommendation, ''),
		       r.server_updated_at, f.server_updated_at, COALESCE(f.kind, 'ISSUE')
		FROM rooms r LEFT JOIN findings f ON f.room_id = r.id AND f.deleted_at IS NULL
		WHERE r.inspection_id = $1 AND r.deleted_at IS NULL
		ORDER BY r.sort_order, f.sort_order, f.created_at`, d.inspectionID)
	if err != nil {
		return nil, err
	}
	findingIdx := map[string][2]int{}
	roomIdx := map[string]int{}
	for rows.Next() {
		var roomID, roomName string
		var fid *string
		var f docxFinding
		var ru time.Time
		var fu *time.Time
		var kind string
		if err := rows.Scan(&roomID, &roomName, &fid, &f.title, &f.category, &f.severity, &f.status, &f.observation, &f.implication,
			&f.recommendation, &ru, &fu, &kind); err != nil {
			rows.Close()
			return nil, err
		}
		ri, ok := roomIdx[roomID]
		if !ok {
			ri = len(d.rooms)
			roomIdx[roomID] = ri
			d.rooms = append(d.rooms, docxRoom{name: roomName})
			fmt.Fprintf(fp, "r%s%d|", roomID, ru.UnixNano())
		}
		if fid == nil {
			continue
		}
		fmt.Fprintf(fp, "f%s%d|", *fid, fu.UnixNano())
		if kind == "ROOM_PHOTOS" { // the room's common photos: no text, printed under the room heading
			findingIdx[*fid] = [2]int{ri, -1}
			continue
		}
		// As in the PDF: the site note never reaches the client; an untitled finding is named by its category.
		if strings.TrimSpace(f.title) == "" {
			f.title = f.category
			if f.title == "" {
				f.title = "Finding"
			}
		}
		findingIdx[*fid] = [2]int{ri, len(d.rooms[ri].findings)}
		d.rooms[ri].findings = append(d.rooms[ri].findings, f)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}

	rows, err = s.db.Query(ctx, `SELECT id::text, finding_id::text, sha256, object_key, verified_at IS NOT NULL, archived_at IS NOT NULL,
		COALESCE(caption, ''), server_updated_at
		FROM photos WHERE inspection_id = $1 AND deleted_at IS NULL ORDER BY captured_at`, d.inspectionID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var p docxPhoto
		var fid string
		var key *string
		var verified bool
		var pu time.Time
		if err := rows.Scan(&p.id, &fid, &p.sha, &key, &verified, &p.archived, &p.caption, &pu); err != nil {
			return nil, err
		}
		idx, ok := findingIdx[fid]
		if !ok {
			continue
		}
		if !p.archived && (!verified || key == nil) {
			return nil, errPhotosUploading
		}
		p.key = deref(key)
		fmt.Fprintf(fp, "p%s%s%d%t|", p.id, p.sha, pu.UnixNano(), p.archived)
		if idx[1] < 0 {
			d.rooms[idx[0]].photos = append(d.rooms[idx[0]].photos, p)
			continue
		}
		f := &d.rooms[idx[0]].findings[idx[1]]
		f.photos = append(f.photos, p)
	}
	d.fingerprint = hex.EncodeToString(fp.Sum(nil))[:24]
	return d, rows.Err()
}

// docxFromContent fills d from the version's snapshot: the same texts and photos as its PDF.
func (s *Server) docxFromContent(ctx context.Context, d *docxReport, raw, sha string) (*docxReport, error) {
	var c reportContent
	if err := json.Unmarshal([]byte(raw), &c); err != nil {
		return nil, err
	}
	// Never the snapshot's own report_number: it ends up in the download's file name. Same formula as the phone.
	d.number = reportNumber(c.InspectionDate, d.inspectionID)
	d.client, d.phone, d.address, d.unit, d.propType = c.ClientName, c.ClientPhone, c.PropertyAddress, c.UnitNumber, c.PropertyType
	d.date, d.inspector, d.summary = longDate(c.InspectionDate), c.InspectorName, c.Summary

	var ids []string
	for _, room := range c.Rooms {
		for _, p := range room.Photos {
			ids = append(ids, p.ID)
		}
		for _, f := range room.Findings {
			for _, p := range f.Photos {
				ids = append(ids, p.ID)
			}
		}
	}
	type stored struct {
		key                *string
		verified, archived bool
	}
	photos := map[string]stored{}
	// Only this inspection's photos: a snapshot can't pull in another inspection's files. Deleted ones
	// still count (the version's PDF showed them; originals are immutable).
	rows, err := s.db.Query(ctx, `SELECT id::text, object_key, verified_at IS NOT NULL, archived_at IS NOT NULL FROM photos
		WHERE id::text = ANY($1) AND inspection_id = $2`, ids, d.inspectionID)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var id string
		var p stored
		if err := rows.Scan(&id, &p.key, &p.verified, &p.archived); err != nil {
			rows.Close()
			return nil, err
		}
		photos[id] = p
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}

	fp := sha256.New()
	fmt.Fprintf(fp, "snap%s|%s|%s|", docxLayout, d.reportID, sha)
	photo := func(p reportContentPhoto) (docxPhoto, error) {
		st, ok := photos[p.ID]
		if !ok || (!st.archived && (!st.verified || st.key == nil)) {
			return docxPhoto{}, errPhotosUploading
		}
		fmt.Fprintf(fp, "p%s%t|", p.ID, st.archived)
		return docxPhoto{id: p.ID, sha: p.SHA256, key: deref(st.key), caption: p.Caption, archived: st.archived}, nil
	}
	for _, room := range c.Rooms {
		r := docxRoom{name: room.Name}
		for _, p := range room.Photos {
			dp, err := photo(p)
			if err != nil {
				return nil, err
			}
			r.photos = append(r.photos, dp)
		}
		for _, f := range room.Findings {
			df := docxFinding{title: f.Title, category: f.Category, severity: f.Severity, status: f.Status,
				observation: f.Observation, implication: f.Implication, recommendation: f.Recommendation}
			for _, p := range f.Photos {
				dp, err := photo(p)
				if err != nil {
					return nil, err
				}
				df.photos = append(df.photos, dp)
			}
			r.findings = append(r.findings, df)
		}
		d.rooms = append(d.rooms, r)
	}
	d.fingerprint = hex.EncodeToString(fp.Sum(nil))[:24]
	return d, nil
}

func longDate(day string) string {
	if t, err := time.Parse(dayLayout, day); err == nil {
		return t.Format("2 January 2006")
	}
	return day
}

// reportNumber matches the phone: GRH-<yyMMdd>-<first 4 of the id, upper case>.
func reportNumber(inspectionDate, inspectionID string) string {
	t, err := time.Parse(dayLayout, inspectionDate)
	if err != nil {
		return "GRH-" + strings.ToUpper(inspectionID[:4])
	}
	return "GRH-" + t.Format("060102") + "-" + strings.ToUpper(inspectionID[:4])
}

// GET /v1/reports/{id}/docx — any signed-in user (everyone sees all inspections).
func (s *Server) downloadReportDocx(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !isUUID(id) {
		writeError(w, http.StatusBadRequest, "INVALID", "id must be a UUID")
		return
	}
	d, err := s.loadDocxReport(r.Context(), id)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such report")
		return
	case errors.Is(err, errStaleVersion):
		writeError(w, http.StatusConflict, "NOT_LATEST", "this version was finalized before Word copies were kept; only the latest version of a finalized report can be rebuilt")
		return
	case errors.Is(err, errPhotosUploading):
		writeError(w, http.StatusConflict, "PHOTOS_UPLOADING", "some photos of this report haven't reached the server yet; try again later")
		return
	case err != nil:
		s.internal(w, "docx", err)
		return
	}
	dir := filepath.Join(s.cfg.DataDir, docxCacheDir)
	path := filepath.Join(dir, fmt.Sprintf("%s-%s.docx", id, d.fingerprint))
	if _, err := os.Stat(path); err != nil {
		select {
		case docxBuild <- struct{}{}:
		case <-r.Context().Done():
			return
		}
		err := func() error {
			defer func() { <-docxBuild }()
			if _, err := os.Stat(path); err == nil {
				return nil // built by the request we waited for
			}
			if err := os.MkdirAll(dir, 0o750); err != nil {
				return err
			}
			return s.writeDocx(r.Context(), d, path)
		}()
		if err != nil {
			s.internal(w, "docx", err)
			return
		}
	}
	now := time.Now()
	_ = os.Chtimes(path, now, now) // keeps it in the cache while it's used
	f, err := os.Open(path)
	if err != nil {
		s.internal(w, "docx", err)
		return
	}
	defer f.Close()
	st, _ := f.Stat()
	w.Header().Set("Content-Type", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": fmt.Sprintf("HomeInspection-Report-%s-v%d.docx", d.number, d.version)}))
	w.Header().Set("Cache-Control", "private, no-store")
	http.ServeContent(w, r, "", st.ModTime(), f)
	u := userFrom(r)
	s.auditEvent(r.Context(), "REPORT_DOCX", &u.ID, u.deviceID, "REPORT:"+id, "")
}

// PurgeDocxCache removes cached Word files not downloaded for a week.
func (s *Server) PurgeDocxCache() {
	dir := filepath.Join(s.cfg.DataDir, docxCacheDir)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, e := range entries {
		if info, err := e.Info(); err == nil && time.Since(info.ModTime()) > docxCacheAge {
			_ = os.Remove(filepath.Join(dir, e.Name()))
		}
	}
}

// writeDocx builds the file under a temp name and renames it into place.
func (s *Server) writeDocx(ctx context.Context, d *docxReport, path string) error {
	tmp := path + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	defer os.Remove(tmp)
	zw := zip.NewWriter(f)
	b := &docxBuilder{d: d}

	// Photos first: each becomes word/media/N.jpg; the document refers to them by relationship id.
	if err := b.addImage(zw, "logo", logoJPEG, 192, 192); err != nil {
		return err
	}
	for _, room := range d.rooms {
		for _, p := range room.allPhotos() {
			if err := ctx.Err(); err != nil {
				return err
			}
			if p.archived {
				continue
			}
			orig, err := os.ReadFile(filepath.Join(s.cfg.DataDir, p.key))
			if err != nil {
				return err
			}
			jpg, w, h, err := reportJPEG(orig)
			if err != nil {
				s.log.Error("docx photo", "photo", p.id, "err", err)
				continue // unreadable: left out, like the PDF does
			}
			if err := b.addImage(zw, p.id, jpg, w, h); err != nil {
				return err
			}
		}
	}
	parts := map[string]string{
		"[Content_Types].xml":          contentTypesXML,
		"_rels/.rels":                  rootRelsXML,
		"word/styles.xml":              stylesXML,
		"word/settings.xml":            settingsXML,
		"word/document.xml":            b.document(),
		"word/header1.xml":             b.header(),
		"word/footer1.xml":             b.footer(),
		"word/_rels/document.xml.rels": b.documentRels(),
		"word/_rels/header1.xml.rels":  b.headerRels(),
		"docProps/core.xml":            b.coreProps(),
	}
	for _, name := range []string{"[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "word/document.xml", "word/styles.xml",
		"word/settings.xml", "word/header1.xml", "word/footer1.xml", "word/_rels/document.xml.rels", "word/_rels/header1.xml.rels"} {
		fw, err := zw.Create(name)
		if err != nil {
			return err
		}
		if _, err := io.WriteString(fw, parts[name]); err != nil {
			return err
		}
	}
	if err := zw.Close(); err != nil {
		return err
	}
	if err := f.Sync(); err != nil {
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// ---- Document XML ---------------------------------------------------------------------------

type docxImage struct {
	rel  string
	w, h int
}

type docxBuilder struct {
	d      *docxReport
	images map[string]docxImage // photo id (or "logo") → relationship
	rels   []string
	body   strings.Builder
	nextID int
	photoN int
}

func (b *docxBuilder) addImage(zw *zip.Writer, id string, jpg []byte, w, h int) error {
	if b.images == nil {
		b.images = map[string]docxImage{}
	}
	n := len(b.images) + 1
	name := fmt.Sprintf("media/image%d.jpg", n)
	// JPEGs are already compressed: store them, don't deflate again.
	fw, err := zw.CreateHeader(&zip.FileHeader{Name: "word/" + name, Method: zip.Store})
	if err != nil {
		return err
	}
	if _, err := fw.Write(jpg); err != nil {
		return err
	}
	rel := fmt.Sprintf("rIdImg%d", n)
	b.images[id] = docxImage{rel: rel, w: w, h: h}
	b.rels = append(b.rels, fmt.Sprintf(`<Relationship Id="%s" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="%s"/>`, rel, name))
	return nil
}

var invalidXML = regexp.MustCompile("[\x00-\x08\x0B\x0C\x0E-\x1F]")

func esc(s string) string {
	s = invalidXML.ReplaceAllString(s, "")
	r := strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;")
	return r.Replace(s)
}

type runStyle struct {
	bold  bool
	size  float64 // points
	color string
	shade string // background fill (severity chips)
	caps  bool
}

func run(text string, st runStyle) string {
	var p strings.Builder
	p.WriteString("<w:r><w:rPr>")
	if st.bold {
		p.WriteString("<w:b/>")
	}
	if st.caps {
		p.WriteString("<w:caps/>")
	}
	if st.color != "" {
		fmt.Fprintf(&p, `<w:color w:val="%s"/>`, st.color)
	}
	if st.size > 0 {
		fmt.Fprintf(&p, `<w:sz w:val="%d"/>`, int(st.size*2))
	}
	if st.shade != "" {
		fmt.Fprintf(&p, `<w:shd w:val="clear" w:color="auto" w:fill="%s"/>`, st.shade)
	}
	p.WriteString("</w:rPr>")
	for i, line := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n") {
		if i > 0 {
			p.WriteString("<w:br/>")
		}
		fmt.Fprintf(&p, `<w:t xml:space="preserve">%s</w:t>`, esc(line))
	}
	p.WriteString("</w:r>")
	return p.String()
}

// para writes a paragraph; spacing in points (before, after); extra goes inside <w:pPr>.
func para(before, after float64, extra string, runs ...string) string {
	props := append(splitElements(extra), fmt.Sprintf(`<w:spacing w:before="%d" w:after="%d"/>`, int(before*20), int(after*20)))
	slices.SortStableFunc(props, func(a, b string) int { return pPrRank(a) - pPrRank(b) })
	return `<w:p><w:pPr>` + strings.Join(props, "") + `</w:pPr>` + strings.Join(runs, "") + `</w:p>`
}

// Word rejects paragraph properties out of schema order (CT_PPrBase), so they are sorted.
var pPrOrder = []string{"keepNext", "keepLines", "pageBreakBefore", "pBdr", "shd", "tabs", "spacing", "ind", "jc"}

func pPrRank(el string) int {
	name := strings.TrimPrefix(el, "<w:")
	if i := strings.IndexAny(name, " />"); i >= 0 {
		name = name[:i]
	}
	if i := slices.Index(pPrOrder, name); i >= 0 {
		return i
	}
	return len(pPrOrder)
}

// splitElements splits a run of sibling XML elements ("<w:a/><w:b>…</w:b>") into its elements.
func splitElements(x string) []string {
	var out []string
	for x != "" {
		name := strings.TrimPrefix(x, "<w:")
		name = name[:strings.IndexAny(name, " />")]
		end := strings.Index(x, ">") + 1
		if x[end-2] != '/' { // has children: up to its closing tag
			close := "</w:" + name + ">"
			end = strings.Index(x, close) + len(close)
		}
		out = append(out, x[:end])
		x = x[end:]
	}
	return out
}

func (b *docxBuilder) heading(text string) {
	b.body.WriteString(para(14, 8, `<w:keepNext/>`, run(text, runStyle{bold: true, size: 14, color: cGreen})))
}

func (b *docxBuilder) subheading(text string) {
	b.body.WriteString(para(8, 4, `<w:keepNext/>`, run(text, runStyle{bold: true, size: 11, color: cDeep})))
}

func (b *docxBuilder) text(text string, size float64, color string) {
	b.body.WriteString(para(0, 6, "", run(text, runStyle{size: size, color: color})))
}

func chip(severity string) string {
	c := severityColors[severity]
	return run(" "+severityLabel[severity]+" ", runStyle{bold: true, size: 8, color: c[1], shade: c[0]})
}

// drawing is an inline picture of w×h points.
func (b *docxBuilder) drawing(img docxImage, wPt, hPt float64) string {
	b.nextID++
	cx, cy := int(wPt*12700), int(hPt*12700) // EMU per point
	return fmt.Sprintf(`<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="%d" cy="%d"/>`+
		`<wp:docPr id="%d" name="Picture %d"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">`+
		`<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">`+
		`<pic:nvPicPr><pic:cNvPr id="%d" name="image%d.jpg"/><pic:cNvPicPr/></pic:nvPicPr>`+
		`<pic:blipFill><a:blip r:embed="%s"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>`+
		`<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="%d" cy="%d"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom>`+
		`<a:ln w="6350"><a:solidFill><a:srgbClr val="%s"/></a:solidFill></a:ln></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>`,
		cx, cy, b.nextID, b.nextID, b.nextID, b.nextID, img.rel, cx, cy, cLine)
}

// Page geometry (A4, same margins as the PDF), in points.
const (
	pageWPt    = 595.28
	marginPt   = 44.0
	contentWPt = pageWPt - 2*marginPt
	gutterPt   = 14.0
	maxPhotoH  = 230.0
)

func twips(pt float64) int { return int(pt * 20) }

func cell(widthPt float64, shade string, content string) string {
	sh := ""
	if shade != "" {
		sh = fmt.Sprintf(`<w:shd w:val="clear" w:color="auto" w:fill="%s"/>`, shade)
	}
	return fmt.Sprintf(`<w:tc><w:tcPr><w:tcW w:w="%d" w:type="dxa"/>%s</w:tcPr>%s</w:tc>`, twips(widthPt), sh, content)
}

func table(widths []float64, rows ...string) string {
	var grid strings.Builder
	total := 0.0
	for _, w := range widths {
		fmt.Fprintf(&grid, `<w:gridCol w:w="%d"/>`, twips(w))
		total += w
	}
	return fmt.Sprintf(`<w:tbl><w:tblPr><w:tblW w:w="%d" w:type="dxa"/><w:tblLayout w:type="fixed"/>`+
		`<w:tblCellMar><w:left w:w="100" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar></w:tblPr>`+
		`<w:tblGrid>%s</w:tblGrid>%s</w:tbl>`, twips(total), grid.String(), strings.Join(rows, ""))
}

func row(cells ...string) string {
	return `<w:tr><w:trPr><w:cantSplit/></w:trPr>` + strings.Join(cells, "") + `</w:tr>`
}

func (b *docxBuilder) document() string {
	d := b.d
	propertyLine := d.address
	if d.unit != "" {
		propertyLine = d.unit + ", " + d.address
	}
	// Cover band: logo | title block | report number and version.
	logo := para(0, 0, "", b.drawing(b.images["logo"], 64, 64))
	title := para(4, 2, "", run("PROPERTY INSPECTION REPORT", runStyle{bold: true, size: 8, color: cFresh})) +
		para(0, 2, "", run(propertyLine, runStyle{bold: true, size: 16, color: cDeep})) +
		para(0, 4, "", run("Inspected on "+d.date, runStyle{size: 10, color: cMuted}))
	right := `<w:pPr><w:jc w:val="right"/></w:pPr>`
	meta := fmt.Sprintf(`<w:p>%s%s</w:p><w:p>%s%s</w:p><w:p>%s%s</w:p><w:p>%s%s</w:p>`,
		right, run("REPORT NO.", runStyle{bold: true, size: 7.5, color: cMuted}),
		right, run(d.number, runStyle{bold: true, size: 11, color: cDeep}),
		right, run("VERSION", runStyle{bold: true, size: 7.5, color: cMuted}),
		right, run(fmt.Sprint(d.version), runStyle{bold: true, size: 11, color: cDeep}))
	b.body.WriteString(table([]float64{84, contentWPt - 84 - 120, 120},
		row(cell(84, cCream, logo), cell(contentWPt-84-120, cCream, title), cell(120, cCream, meta))))
	b.body.WriteString(para(0, 10, fmt.Sprintf(`<w:pBdr><w:top w:val="single" w:sz="18" w:space="1" w:color="%s"/></w:pBdr>`, cGreen)))

	// Prepared for: label above value, two columns.
	b.heading("Prepared for")
	field := func(label, value string) string {
		if strings.TrimSpace(value) == "" {
			value = "—"
		}
		return para(0, 0, "", run(strings.ToUpper(label), runStyle{bold: true, size: 7.5, color: cMuted})) +
			para(0, 8, "", run(value, runStyle{size: 10.5, color: cText}))
	}
	half := (contentWPt - gutterPt) / 2
	b.body.WriteString(table([]float64{half + gutterPt/2, half + gutterPt/2},
		row(cell(half+gutterPt/2, "", field("Client", d.client)), cell(half+gutterPt/2, "", field("Phone", d.phone))),
		row(cell(half+gutterPt/2, "", field("Property address", d.address)), cell(half+gutterPt/2, "", "<w:p/>")),
		row(cell(half+gutterPt/2, "", field("Unit / flat", d.unit)), cell(half+gutterPt/2, "", field("Property type", d.propType))),
		row(cell(half+gutterPt/2, "", field("Inspection date", d.date)), cell(half+gutterPt/2, "", field("Inspector", d.inspector)))))

	// Summary.
	b.heading("Inspection summary")
	if s := strings.TrimSpace(d.summary); s != "" {
		b.text(s, 10.5, cText)
	}
	findings, photos := 0, 0
	counts := map[string]int{}
	for _, r := range d.rooms {
		findings += len(r.findings)
		photos += len(r.allPhotos())
		for _, f := range r.findings {
			counts[f.severity]++
		}
	}
	b.text(fmt.Sprintf("Areas inspected: %d     Findings recorded: %d     Photos: %d", len(d.rooms), findings, photos), 10.5, cText)
	var chips []string
	for _, sev := range severityOrder {
		if counts[sev] > 0 {
			c := severityColors[sev]
			chips = append(chips, run(fmt.Sprintf(" %s  %d ", severityLabel[sev], counts[sev]), runStyle{bold: true, size: 8, color: c[1], shade: c[0]}), run("   ", runStyle{size: 8}))
		}
	}
	if len(chips) > 0 {
		b.body.WriteString(para(2, 10, "", chips...))
	}
	b.subheading("Significant findings")
	hasSignificant := false
	for _, sev := range []string{"URGENT", "MAJOR", "MODERATE"} {
		for _, r := range d.rooms {
			for _, f := range r.findings {
				if f.severity == sev {
					hasSignificant = true
					b.body.WriteString(para(0, 4, "", chip(sev), run("  "+f.title+" — "+r.name, runStyle{size: 10, color: cText})))
				}
			}
		}
	}
	if !hasSignificant {
		b.text("No findings rated Moderate, High or Urgent were recorded.", 10, cMuted)
	}

	// Rooms.
	b.body.WriteString(para(0, 8, `<w:keepNext/><w:pageBreakBefore/>`, run("Room-by-room findings", runStyle{bold: true, size: 14, color: cGreen})))
	tab := fmt.Sprintf(`<w:tabs><w:tab w:val="right" w:pos="%d"/></w:tabs>`, twips(contentWPt-10))
	for i, r := range d.rooms {
		count := "No findings"
		switch n := len(r.findings); {
		case n == 1:
			count = "1 finding"
		case n > 1:
			count = fmt.Sprintf("%d findings", n)
		}
		b.body.WriteString(para(10, 8, `<w:keepNext/>`+tab+fmt.Sprintf(`<w:shd w:val="clear" w:color="auto" w:fill="%s"/><w:ind w:left="100" w:right="100"/>`, cSage),
			run(fmt.Sprintf("%d. %s", i+1, r.name), runStyle{bold: true, size: 12, color: cDeep}),
			`<w:r><w:tab/></w:r>`, run(count, runStyle{size: 9, color: cDeep})))
		if len(r.photos) > 0 {
			b.body.WriteString(para(0, 2, `<w:keepNext/>`, run("ROOM PHOTOS", runStyle{bold: true, size: 7.5, color: cGreen})))
			b.photoGrid(r.photos)
		}
		if len(r.findings) == 0 {
			b.text("No findings recorded for this area.", 10, cMuted)
		}
		for j, f := range r.findings {
			b.finding(fmt.Sprintf("%d.%d", i+1, j+1), f)
		}
	}

	// Scope.
	b.heading("Scope and limitations")
	for _, p := range scopeText {
		b.text(p, 9.5, cText)
	}

	sect := fmt.Sprintf(`<w:sectPr><w:headerReference w:type="default" r:id="rIdHeader"/><w:footerReference w:type="default" r:id="rIdFooter"/>`+
		`<w:footerReference w:type="first" r:id="rIdFooter"/><w:pgSz w:w="11906" w:h="16838"/>`+
		`<w:pgMar w:top="%d" w:right="%d" w:bottom="%d" w:left="%d" w:header="400" w:footer="400" w:gutter="0"/><w:titlePg/></w:sectPr>`,
		twips(60), twips(marginPt), twips(60), twips(marginPt))
	return xmlHeader + `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" ` +
		`xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" ` +
		`xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">` +
		`<w:body>` + b.body.String() + sect + `</w:body></w:document>`
}

func (b *docxBuilder) finding(number string, f docxFinding) {
	runs := []string{run(number+"  "+f.title, runStyle{bold: true, size: 11.5, color: cDeep})}
	if f.severity != "" && severityLabel[f.severity] != "" {
		runs = append(runs, run("   ", runStyle{}), chip(f.severity))
	}
	b.body.WriteString(para(6, 2, `<w:keepNext/>`, runs...))
	var meta []string
	if f.category != "" {
		meta = append(meta, f.category)
	}
	if l := findingStatusLabel[f.status]; l != "" {
		meta = append(meta, l)
	}
	if len(meta) > 0 {
		b.body.WriteString(para(0, 4, `<w:keepNext/>`, run(strings.Join(meta, " · "), runStyle{size: 9, color: cMuted})))
	}
	for _, l := range [][2]string{{"Observation", f.observation}, {"Implication", f.implication}, {"Recommendation", f.recommendation}} {
		if strings.TrimSpace(l[1]) == "" {
			continue
		}
		b.body.WriteString(para(4, 0, `<w:keepNext/>`, run(strings.ToUpper(l[0]), runStyle{bold: true, size: 7.5, color: cGreen})))
		b.text(strings.TrimSpace(l[1]), 10, cText)
	}
	b.photoGrid(f.photos)
	b.body.WriteString(para(4, 12, fmt.Sprintf(`<w:pBdr><w:bottom w:val="single" w:sz="4" w:space="1" w:color="%s"/></w:pBdr>`, cLine)))
}

// photoGrid: two per row, aspect kept, "Photo N · caption" below, as in the PDF (numbered across the report).
func (b *docxBuilder) photoGrid(photos []docxPhoto) {
	colW := (contentWPt - gutterPt) / 2
	var cells []string
	flush := func() {
		if len(cells) == 0 {
			return
		}
		for len(cells) < 2 {
			cells = append(cells, cell(colW+gutterPt/2, "", "<w:p/>"))
		}
		b.body.WriteString(table([]float64{colW + gutterPt/2, colW + gutterPt/2}, row(cells...)))
		cells = nil
	}
	for _, p := range photos {
		b.photoN++
		caption := fmt.Sprintf("Photo %d", b.photoN)
		if strings.TrimSpace(p.caption) != "" {
			caption += " · " + strings.TrimSpace(p.caption)
		}
		var content string
		img, ok := b.images[p.id]
		switch {
		case ok:
			scale := min(colW/float64(img.w), maxPhotoH/float64(img.h))
			content = para(4, 2, `<w:jc w:val="center"/>`, b.drawing(img, float64(img.w)*scale, float64(img.h)*scale))
		case p.archived:
			content = para(4, 2, "", run("(photo moved to the offline archive)", runStyle{size: 9, color: cMuted}))
		default:
			content = para(4, 2, "", run("(photo could not be read)", runStyle{size: 9, color: cMuted}))
		}
		content += para(0, 8, "", run(caption, runStyle{size: 8.5, color: cMuted}))
		cells = append(cells, cell(colW+gutterPt/2, "", content))
		if len(cells) == 2 {
			flush()
		}
	}
	flush()
}

func (b *docxBuilder) ref() string {
	return fmt.Sprintf("Report %s · Version %d", b.d.number, b.d.version)
}

// header: pages 2+ (the first page has the cover band instead; <w:titlePg/>).
func (b *docxBuilder) header() string {
	tab := fmt.Sprintf(`<w:tabs><w:tab w:val="right" w:pos="%d"/></w:tabs>`, twips(contentWPt))
	logo := b.drawingHeader()
	return xmlHeader + `<w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" ` +
		`xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" ` +
		`xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">` +
		para(0, 0, tab+fmt.Sprintf(`<w:pBdr><w:bottom w:val="single" w:sz="4" w:space="4" w:color="%s"/></w:pBdr>`, cLine),
			logo, run("  Home Inspection", runStyle{bold: true, size: 9.5, color: cDeep}), `<w:r><w:tab/></w:r>`,
			run(b.ref(), runStyle{size: 8.5, color: cMuted})) +
		`</w:hdr>`
}

func (b *docxBuilder) drawingHeader() string {
	img := b.images["logo"]
	img.rel = "rIdLogo" // the header has its own relationships part
	return b.drawing(img, 18, 18)
}

func (b *docxBuilder) footer() string {
	tab := fmt.Sprintf(`<w:tabs><w:tab w:val="right" w:pos="%d"/></w:tabs>`, twips(contentWPt))
	st := runStyle{size: 8, color: cMuted}
	field := func(instr string) string {
		return `<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> ` + instr +
			` </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>` + run("1", st) + `<w:r><w:fldChar w:fldCharType="end"/></w:r>`
	}
	return xmlHeader + `<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">` +
		para(0, 0, tab+fmt.Sprintf(`<w:pBdr><w:top w:val="single" w:sz="4" w:space="4" w:color="%s"/></w:pBdr>`, cLine),
			run("Home Inspection · "+b.ref(), st), `<w:r><w:tab/></w:r>`,
			run("Page ", st), field("PAGE"), run(" of ", st), field("NUMPAGES")) +
		`</w:ftr>`
}

func (b *docxBuilder) documentRels() string {
	return xmlHeader + `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
		`<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>` +
		`<Relationship Id="rIdSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>` +
		`<Relationship Id="rIdHeader" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>` +
		`<Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>` +
		strings.Join(b.rels, "") + `</Relationships>`
}

func (b *docxBuilder) headerRels() string {
	return xmlHeader + `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
		`<Relationship Id="rIdLogo" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.jpg"/>` +
		`</Relationships>`
}

func (b *docxBuilder) coreProps() string {
	return xmlHeader + `<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" ` +
		`xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" ` +
		`xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">` +
		`<dc:title>` + esc("Home Inspection inspection report "+b.d.number+" – Version "+fmt.Sprint(b.d.version)) + `</dc:title>` +
		`<dc:creator>Home Inspection</dc:creator></cp:coreProperties>`
}

const xmlHeader = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` + "\n"

const contentTypesXML = xmlHeader + `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">` +
	`<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>` +
	`<Default Extension="xml" ContentType="application/xml"/>` +
	`<Default Extension="jpg" ContentType="image/jpeg"/>` +
	`<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>` +
	`<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>` +
	`<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>` +
	`<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>` +
	`<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>` +
	`<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>` +
	`</Types>`

const rootRelsXML = xmlHeader + `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
	`<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>` +
	`<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>` +
	`</Relationships>`

const settingsXML = xmlHeader + `<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">` +
	`<w:defaultTabStop w:val="720"/><w:compat><w:compatSetting w:name="compatibilityMode" ` +
	`w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>`

// Default font: Calibri-like sans (Word substitutes on other systems), 10.5 pt body text.
const stylesXML = xmlHeader + `<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">` +
	`<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/>` +
	`<w:sz w:val="21"/><w:szCs w:val="21"/><w:lang w:val="en-IN"/></w:rPr></w:rPrDefault>` +
	`<w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>` +
	`<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>` +
	`<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:tblPr><w:tblInd w:w="0" w:type="dxa"/>` +
	`<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>` +
	`</w:styles>`

// RunDocxCachePurge clears old cached Word files now and then daily until ctx ends.
func (s *Server) RunDocxCachePurge(ctx context.Context) {
	tick := time.NewTicker(24 * time.Hour)
	defer tick.Stop()
	for {
		s.PurgeDocxCache()
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}
