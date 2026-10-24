package api

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"net/http"
	"os"
	"strings"
	"testing"
)

// webDo sends a request as the browser would: the session cookie, plus X-Home-Inspection-Web unless noHeader.
func (h *harness) webDo(method, path, cookie string, body any, noHeader bool) (*http.Response, []byte) {
	h.t.Helper()
	var rd io.Reader
	if body != nil {
		raw, _ := json.Marshal(body)
		rd = bytes.NewReader(raw)
	}
	req, _ := http.NewRequest(method, h.srv.URL+path, rd)
	if cookie != "" {
		req.AddCookie(&http.Cookie{Name: webCookie, Value: cookie})
	}
	if !noHeader {
		req.Header.Set("X-Home-Inspection-Web", "1")
	}
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := client.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp, data
}

func (h *harness) webLogin(code string) string {
	h.t.Helper()
	resp, _ := h.webDo("POST", "/v1/auth/web-login", "", map[string]string{"employee_code": code, "password": "password-123"}, false)
	for _, c := range resp.Cookies() {
		if c.Name == webCookie && c.Value != "" {
			if !c.HttpOnly || !c.Secure || c.SameSite != http.SameSiteStrictMode {
				h.t.Fatalf("weak cookie: %+v", c)
			}
			return c.Value
		}
	}
	h.t.Fatalf("web login %s: %d", code, resp.StatusCode)
	return ""
}

func TestWebSessions(t *testing.T) {
	h := setup(t)
	h.user("insp", "INSPECTOR")
	h.user("deva", "ADMIN")

	if resp, _ := h.webDo("POST", "/v1/auth/web-login", "", map[string]string{"employee_code": "insp", "password": "password-123"}, true); resp.StatusCode != 403 {
		t.Fatalf("login without header: %d", resp.StatusCode)
	}
	if resp, _ := h.webDo("POST", "/v1/auth/web-login", "", map[string]string{"employee_code": "insp", "password": "wrong-password"}, false); resp.StatusCode != 401 {
		t.Fatalf("bad password: %d", resp.StatusCode)
	}
	cookie := h.webLogin("insp")
	if resp, _ := h.webDo("GET", "/v1/me", cookie, nil, true); resp.StatusCode != 200 {
		t.Fatalf("GET with cookie: %d", resp.StatusCode)
	}
	// A cross-site form can't add the header: writes without it are refused.
	if resp, _ := h.webDo("POST", "/v1/leave", cookie, leave("CASUAL", "2026-12-01", "2026-12-01", nil), true); resp.StatusCode != 403 {
		t.Fatalf("POST without header: %d", resp.StatusCode)
	}
	if resp, _ := h.webDo("GET", "/v1/admin/calendar/team?month=2026-10", cookie, nil, false); resp.StatusCode != 403 {
		t.Fatalf("inspector reached admin on the web: %d", resp.StatusCode)
	}
	// A web token is not a bearer token, and a phone token is not a cookie.
	if st := h.do("GET", "/v1/me", cookie, nil, nil); st != 401 {
		t.Fatalf("web token as bearer: %d", st)
	}
	phone := h.login("insp")
	if resp, _ := h.webDo("GET", "/v1/me", phone, nil, false); resp.StatusCode != 401 {
		t.Fatalf("phone token as cookie: %d", resp.StatusCode)
	}
	// A browser session can't act as a phone: sync, attendance, tracking and uploads refuse it.
	for _, rt := range [][2]string{{"POST", "/v1/sync/push"}, {"GET", "/v1/sync/changes"}, {"POST", "/v1/attendance"},
		{"POST", "/v1/tracking/batch"}, {"GET", "/v1/face/me"}, {"PUT", "/v1/reports/" + newUUID() + "/pdf"}, {"POST", "/v1/ai/summary"}} {
		if resp, _ := h.webDo(rt[0], rt[1], cookie, map[string]any{}, false); resp.StatusCode != 403 {
			t.Fatalf("%s %s with a web session: %d", rt[0], rt[1], resp.StatusCode)
		}
	}
	admin := h.webLogin("deva")
	// The admin pages brought to the web: allowed for a web session (admins only), nothing more.
	for _, rt := range [][2]string{{"GET", "/v1/admin/users"}, {"GET", "/v1/admin/inspections"}, {"GET", "/v1/admin/devices"},
		{"GET", "/v1/admin/audit"}, {"GET", "/v1/admin/storage"}, {"GET", "/v1/admin/attendance?from=0&to=1000"}} {
		if resp, _ := h.webDo(rt[0], rt[1], admin, nil, false); resp.StatusCode != 200 {
			t.Fatalf("admin %s %s on the web: %d", rt[0], rt[1], resp.StatusCode)
		}
		if resp, _ := h.webDo(rt[0], rt[1], cookie, nil, false); resp.StatusCode != 403 && resp.StatusCode != 401 {
			t.Fatalf("inspector %s %s on the web: %d", rt[0], rt[1], resp.StatusCode)
		}
	}
	for _, rt := range [][2]string{{"POST", "/v1/attendance"}, {"GET", "/v1/attendance"}, {"PUT", "/v1/admin/ai/key"},
		{"GET", "/v1/admin/exports/1/zip"}, {"GET", "/v1/admin/tracking/team"}} {
		if resp, _ := h.webDo(rt[0], rt[1], admin, map[string]any{}, false); resp.StatusCode != 403 {
			t.Fatalf("admin %s %s should stay app-only: %d", rt[0], rt[1], resp.StatusCode)
		}
	}
	if resp, _ := h.webDo("GET", "/v1/admin/calendar/team?month=2026-10", admin, nil, false); resp.StatusCode != 200 {
		t.Fatalf("admin on the web: %d", resp.StatusCode)
	}
	resp, _ := h.webDo("POST", "/v1/auth/logout", cookie, nil, false)
	if resp.StatusCode != 204 || len(resp.Cookies()) == 0 || resp.Cookies()[0].MaxAge >= 0 {
		t.Fatalf("logout: %d %+v", resp.StatusCode, resp.Cookies())
	}
	if resp, _ := h.webDo("GET", "/v1/me", cookie, nil, false); resp.StatusCode != 401 {
		t.Fatalf("after logout: %d", resp.StatusCode)
	}

	// Static pages: served with a strict CSP, also while the app is locked (they show the lock message).
	resp, body := h.webDo("GET", "/web/", "", nil, true)
	if resp.StatusCode != 200 || !strings.Contains(string(body), "app.js") || !strings.Contains(resp.Header.Get("Content-Security-Policy"), "script-src 'self'") {
		t.Fatalf("index: %d %s", resp.StatusCode, resp.Header.Get("Content-Security-Policy"))
	}
	if resp, _ := h.webDo("GET", "/", "", nil, true); resp.StatusCode != 302 || resp.Header.Get("Location") != "/web/" {
		t.Fatalf("root: %d", resp.StatusCode)
	}
	if resp, _ := h.webDo("GET", "/web/../server.go", "", nil, true); resp.StatusCode == 200 {
		t.Fatal("path escape served")
	}
	if resp, _ := h.webDo("GET", "/web/nope.exe", "", nil, true); resp.StatusCode != 404 {
		t.Fatalf("unknown file: %d", resp.StatusCode)
	}
}

func testJPEG(w, h int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{uint8(x), uint8(y), 120, 255})
		}
	}
	var buf bytes.Buffer
	_ = jpeg.Encode(&buf, img, &jpeg.Options{Quality: 90})
	return buf.Bytes()
}

func TestReportsListAndDocx(t *testing.T) {
	h := setup(t)
	h.user("insp", "INSPECTOR")
	h.user("other", "INSPECTOR")
	tok := h.login("insp")
	insp := newUUID()
	photo := h.seedPhoto(tok, insp, "FINALIZED", 5, testJPEG(2400, 1200))
	// A second photo with a caption and odd characters in the texts.
	pdf := []byte("%PDF-1.4 test")
	sum := sha256.Sum256(pdf)
	report := newUUID()
	h.db.Exec(t.Context(), `UPDATE inspections SET report_version = 1, summary = 'Overall <good> & dry', client_name = 'Priya & Co' WHERE id = $1`, insp)
	h.db.Exec(t.Context(), `UPDATE findings SET title = 'Crack "near" sink', severity = 'MAJOR', implication = 'Water may leak'||chr(10)||'into the wall', recommendation = 'Seal it' WHERE inspection_id = $1`, insp)
	h.db.Exec(t.Context(), `UPDATE photos SET caption = 'Under the sink' WHERE id = $1`, photo)
	r := h.push(tok, map[string]any{"type": "REPORT", "id": report, "base_revision": 0, "mutation_id": "rep:1", "data": map[string]any{
		"inspection_id": insp, "version": 1, "content_hash": hex.EncodeToString(sum[:]), "file_size_bytes": len(pdf), "generated_at": 5, "finalized_at": 5}})
	if r[0].Status != "OK" {
		t.Fatalf("report push: %+v", r[0])
	}
	if st := h.do("PUT", "/v1/reports/"+report+"/pdf", tok, pdf, nil); st != 200 {
		t.Fatalf("pdf upload: %d", st)
	}

	// Any employee sees every inspection and can download both files.
	cookie := h.webLogin("other")
	resp, body := h.webDo("GET", "/v1/reports?q=priya", cookie, nil, false)
	var list struct {
		Inspections []struct {
			ID     string `json:"id"`
			Number string `json:"report_number"`
			Report *struct {
				ID   string `json:"id"`
				PDF  string `json:"pdf"`
				Docx bool   `json:"docx"`
			} `json:"report"`
		} `json:"inspections"`
	}
	json.Unmarshal(body, &list)
	if resp.StatusCode != 200 || len(list.Inspections) != 1 || list.Inspections[0].Report == nil || list.Inspections[0].Report.PDF != "AVAILABLE" || !list.Inspections[0].Report.Docx {
		t.Fatalf("reports: %d %s", resp.StatusCode, body)
	}
	var withVersions struct {
		Inspections []struct {
			Versions []struct {
				Version int64 `json:"version"`
				Docx    bool  `json:"docx"`
			} `json:"versions"`
		} `json:"inspections"`
	}
	json.Unmarshal(body, &withVersions)
	if v := withVersions.Inspections[0].Versions; len(v) != 1 || v[0].Version != 1 || !v[0].Docx {
		t.Fatalf("versions: %s", body)
	}
	if want := "GRH-261020-" + strings.ToUpper(insp[:4]); list.Inspections[0].Number != want {
		t.Fatalf("number %s, want %s", list.Inspections[0].Number, want)
	}
	resp, body = h.webDo("GET", "/v1/reports/"+report+"/pdf", cookie, nil, true)
	if resp.StatusCode != 200 || !bytes.Equal(body, pdf) || !strings.Contains(resp.Header.Get("Content-Disposition"), "HomeInspection-Report-GRH-261020-") {
		t.Fatalf("pdf: %d %q", resp.StatusCode, resp.Header.Get("Content-Disposition"))
	}

	resp, body = h.webDo("GET", "/v1/reports/"+report+"/docx", cookie, nil, true)
	if resp.StatusCode != 200 {
		t.Fatalf("docx: %d %s", resp.StatusCode, body)
	}
	if out := os.Getenv("DOCX_SAMPLE_OUT"); out != "" { // manual check: open the file in Word
		_ = os.WriteFile(out, body, 0o644)
	}
	zr, err := zip.NewReader(bytes.NewReader(body), int64(len(body)))
	if err != nil {
		t.Fatal(err)
	}
	files := map[string][]byte{}
	for _, f := range zr.File {
		rc, _ := f.Open()
		files[f.Name], _ = io.ReadAll(rc)
		rc.Close()
	}
	doc := string(files["word/document.xml"])
	for _, want := range []string{"Priya &amp; Co", "Overall &lt;good&gt; &amp; dry", "Crack &quot;near&quot; sink", "High",
		"Water may leak</w:t><w:br/>", "Photo 1 · Under the sink", "Kitchen", "Scope and limitations", `r:embed="rIdImg2"`} {
		if !strings.Contains(doc, want) {
			t.Errorf("document.xml lacks %q", want)
		}
	}
	for _, part := range []string{"[Content_Types].xml", "word/styles.xml", "word/header1.xml", "word/footer1.xml", "word/media/image1.jpg", "word/media/image2.jpg"} {
		if files[part] == nil {
			t.Errorf("missing part %s", part)
		}
	}
	// XML parts must parse.
	for name, data := range files {
		if strings.HasSuffix(name, ".xml") || strings.HasSuffix(name, ".rels") {
			if err := xmlCheck(data); err != nil {
				t.Errorf("%s: %v", name, err)
			}
		}
	}
	// The photo is report-size: longest edge 1600 px.
	cfg, err := jpeg.DecodeConfig(bytes.NewReader(files["word/media/image2.jpg"]))
	if err != nil || cfg.Width != 1600 || cfg.Height != 800 {
		t.Fatalf("photo size: %+v %v", cfg, err)
	}
	// Second download comes from the cache (same bytes).
	if _, again := h.webDo("GET", "/v1/reports/"+report+"/docx", cookie, nil, true); !bytes.Equal(again, body) {
		t.Fatal("cached docx differs")
	}

	// Edited after finalizing: no Word copy of the old version.
	h.db.Exec(t.Context(), `UPDATE inspections SET status = 'IN_PROGRESS' WHERE id = $1`, insp)
	if resp, _ := h.webDo("GET", "/v1/reports/"+report+"/docx", cookie, nil, true); resp.StatusCode != 409 {
		t.Fatalf("stale version: %d", resp.StatusCode)
	}

	// Version 2 finalized: both versions listed (newest first); PDF for both, Word only for v2.
	report2 := newUUID()
	h.push(tok, map[string]any{"type": "REPORT", "id": report2, "base_revision": 0, "mutation_id": "rep:2", "data": map[string]any{
		"inspection_id": insp, "version": 2, "content_hash": hex.EncodeToString(sum[:]), "file_size_bytes": len(pdf), "generated_at": 6, "finalized_at": 6}})
	h.do("PUT", "/v1/reports/"+report2+"/pdf", tok, pdf, nil)
	h.db.Exec(t.Context(), `UPDATE inspections SET status = 'FINALIZED', report_version = 2 WHERE id = $1`, insp)
	_, body = h.webDo("GET", "/v1/reports", cookie, nil, false)
	json.Unmarshal(body, &withVersions)
	v := withVersions.Inspections[0].Versions
	if len(v) != 2 || v[0].Version != 2 || !v[0].Docx || v[1].Version != 1 || v[1].Docx {
		t.Fatalf("two versions: %s", body)
	}
	if resp, _ := h.webDo("GET", "/v1/reports/"+report+"/pdf", cookie, nil, true); resp.StatusCode != 200 {
		t.Fatalf("old version pdf: %d", resp.StatusCode)
	}
}

func xmlCheck(data []byte) error {
	dec := xml.NewDecoder(bytes.NewReader(data))
	for {
		_, err := dec.Token()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
	}
}

func TestReportJPEGRefusesHugeImages(t *testing.T) {
	// A valid JPEG header declaring 20000×20000 (400 MP): refused before decoding.
	small := testJPEG(8, 8)
	i := bytes.Index(small, []byte{0xFF, 0xC0}) // SOF0: length(2) precision(1) height(2) width(2)
	big := append([]byte{}, small...)
	big[i+5], big[i+6], big[i+7], big[i+8] = 0x4E, 0x20, 0x4E, 0x20
	if _, _, _, err := reportJPEG(big); err == nil || !strings.Contains(err.Error(), "too large") {
		t.Fatalf("huge image: %v", err)
	}
}

func TestExifOrientation(t *testing.T) {
	// A minimal JPEG header with an Exif APP1 block: big-endian TIFF, one IFD entry, orientation 6.
	tiff := []byte{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0}
	app1 := append([]byte("Exif\x00\x00"), tiff...)
	seg := append([]byte{0xFF, 0xE1, byte((len(app1) + 2) >> 8), byte(len(app1) + 2)}, app1...)
	jpg := append(append([]byte{0xFF, 0xD8}, seg...), 0xFF, 0xD9)
	if o := exifOrientation(jpg); o != 6 {
		t.Fatalf("orientation %d", o)
	}
	img := image.NewRGBA(image.Rect(0, 0, 4, 2))
	img.Set(0, 0, color.RGBA{255, 0, 0, 255}) // top-left red
	out := orient(img, 6)                     // rotate 90° clockwise: becomes top-right of a 2×4 image
	if out.Bounds().Dx() != 2 || out.Bounds().Dy() != 4 || out.RGBAAt(1, 0).R != 255 {
		t.Fatalf("rotation: %v %v", out.Bounds(), out.RGBAAt(1, 0))
	}
}

func TestParagraphPropertiesInSchemaOrder(t *testing.T) {
	got := para(2, 4, `<w:jc w:val="center"/><w:pBdr><w:top w:val="single"/></w:pBdr><w:keepNext/>`)
	want := `<w:p><w:pPr><w:keepNext/><w:pBdr><w:top w:val="single"/></w:pBdr><w:spacing w:before="40" w:after="80"/><w:jc w:val="center"/></w:pPr></w:p>`
	if got != want {
		t.Fatalf("got  %s\nwant %s", got, want)
	}
}

// A version's Word copy comes from the content its phone uploaded, so it survives reopening and
// matches the PDF even when the live data differs.
func TestReportContentSnapshot(t *testing.T) {
	h := setup(t)
	h.user("insp", "INSPECTOR")
	h.user("other", "INSPECTOR")
	tok, otherTok := h.login("insp"), h.login("other")
	insp := newUUID()
	photo := h.seedPhoto(tok, insp, "FINALIZED", 5, testJPEG(800, 600))
	var photoSHA string
	h.db.QueryRow(t.Context(), `SELECT sha256 FROM photos WHERE id = $1`, photo).Scan(&photoSHA)
	pdf := []byte("%PDF-1.4 snapshot")
	sum := sha256.Sum256(pdf)
	report := newUUID()
	h.db.Exec(t.Context(), `UPDATE inspections SET report_version = 1 WHERE id = $1`, insp)
	h.push(tok, map[string]any{"type": "REPORT", "id": report, "base_revision": 0, "mutation_id": "rep:1", "data": map[string]any{
		"inspection_id": insp, "version": 1, "content_hash": hex.EncodeToString(sum[:]), "file_size_bytes": len(pdf), "generated_at": 5, "finalized_at": 5}})
	h.do("PUT", "/v1/reports/"+report+"/pdf", tok, pdf, nil)

	content := map[string]any{
		"format": 1, "report_id": report, "inspection_id": insp, "version": 1, "report_number": "GRH\"; evil=1",
		"client_name": "Snapshot Client", "client_phone": "98", "property_address": "1 Lane", "unit_number": "", "property_type": "Flat",
		"inspection_date": "2026-10-20", "inspector_name": "Name INSP", "summary": "As printed",
		"rooms": []any{map[string]any{"name": "Kitchen", "photos": []any{map[string]any{"id": photo, "sha256": photoSHA, "caption": "As captioned"}},
			"findings": []any{map[string]any{
				"title": "Printed title", "category": "Plumbing", "severity": "URGENT", "status": "OPEN", "observation": "Seen on the day",
				"implication": "", "recommendation": "", "photos": []any{}}}}},
	}
	raw, _ := json.Marshal(content)
	if st := h.do("PUT", "/v1/reports/"+report+"/content", otherTok, raw, nil); st != 403 {
		t.Fatalf("someone else's version: %d", st)
	}
	if st := h.do("PUT", "/v1/reports/"+newUUID()+"/content", tok, raw, nil); st != 400 {
		t.Fatalf("report_id mismatch: %d", st)
	}
	if st := h.do("PUT", "/v1/reports/"+report+"/content", tok, raw, nil); st != 200 {
		t.Fatalf("content: %d", st)
	}
	if st := h.do("PUT", "/v1/reports/"+report+"/content", tok, raw, nil); st != 200 {
		t.Fatalf("retry: %d", st)
	}
	content["summary"] = "Changed"
	changed, _ := json.Marshal(content)
	if st := h.do("PUT", "/v1/reports/"+report+"/content", tok, changed, nil); st != 409 {
		t.Fatalf("changed content: %d", st)
	}
	cookie := h.webLogin("other")
	if resp, _ := h.webDo("PUT", "/v1/reports/"+report+"/content", cookie, raw, false); resp.StatusCode != 403 {
		t.Fatalf("web session uploaded content: %d", resp.StatusCode)
	}

	// Reopened and edited since: the live data says something else, the Word copy still matches the PDF.
	h.db.Exec(t.Context(), `UPDATE inspections SET status = 'IN_PROGRESS', summary = 'Edited later' WHERE id = $1`, insp)
	h.db.Exec(t.Context(), `UPDATE findings SET title = 'Edited title' WHERE inspection_id = $1`, insp)
	_, body := h.webDo("GET", "/v1/reports", cookie, nil, false)
	var list struct {
		Inspections []struct {
			Versions []struct {
				Docx bool `json:"docx"`
			} `json:"versions"`
		} `json:"inspections"`
		Next string `json:"next"`
	}
	json.Unmarshal(body, &list)
	if len(list.Inspections) != 1 || !list.Inspections[0].Versions[0].Docx || list.Next != "" {
		t.Fatalf("list: %s", body)
	}
	resp, body := h.webDo("GET", "/v1/reports/"+report+"/docx", cookie, nil, true)
	if resp.StatusCode != 200 {
		t.Fatalf("docx: %d %s", resp.StatusCode, body)
	}
	zr, _ := zip.NewReader(bytes.NewReader(body), int64(len(body)))
	var doc string
	for _, f := range zr.File {
		if f.Name == "word/document.xml" {
			rc, _ := f.Open()
			b, _ := io.ReadAll(rc)
			rc.Close()
			doc = string(b)
		}
	}
	if cd := resp.Header.Get("Content-Disposition"); !strings.Contains(cd, "GRH-261020-"+strings.ToUpper(insp[:4])) {
		t.Errorf("file name from the snapshot: %q", cd) // the server's own report number, never the phone's text
	}
	for _, want := range []string{"Snapshot Client", "As printed", "Printed title", "ROOM PHOTOS", "Photo 1 · As captioned", "GRH-261020-" + strings.ToUpper(insp[:4])} {
		if !strings.Contains(doc, want) {
			t.Errorf("document.xml lacks %q", want)
		}
	}
	if strings.Contains(doc, "Edited") {
		t.Error("Word copy shows later edits")
	}
	if resp, _ := h.webDo("GET", "/v1/reports?before=nonsense", cookie, nil, false); resp.StatusCode != 400 {
		t.Fatalf("bad cursor: %d", resp.StatusCode)
	}
}

// A room's common photos (findings of kind ROOM_PHOTOS) print under the room heading, never as a finding.
func TestDocxRoomPhotosFromLiveData(t *testing.T) {
	h := setup(t)
	h.user("insp", "INSPECTOR")
	tok := h.login("insp")
	insp := newUUID()
	h.seedPhoto(tok, insp, "FINALIZED", 5, testJPEG(800, 600))
	pdf := []byte("%PDF-1.4 rooms")
	sum := sha256.Sum256(pdf)
	report := newUUID()
	h.db.Exec(t.Context(), `UPDATE inspections SET report_version = 1 WHERE id = $1`, insp)
	h.db.Exec(t.Context(), `UPDATE findings SET kind = 'ROOM_PHOTOS' WHERE inspection_id = $1`, insp)
	h.push(tok, map[string]any{"type": "REPORT", "id": report, "base_revision": 0, "mutation_id": "rep:1", "data": map[string]any{
		"inspection_id": insp, "version": 1, "content_hash": hex.EncodeToString(sum[:]), "file_size_bytes": len(pdf), "generated_at": 5, "finalized_at": 5}})
	h.do("PUT", "/v1/reports/"+report+"/pdf", tok, pdf, nil)
	resp, body := h.webDo("GET", "/v1/reports/"+report+"/docx", h.webLogin("insp"), nil, true)
	if resp.StatusCode != 200 {
		t.Fatalf("docx: %d %s", resp.StatusCode, body)
	}
	zr, _ := zip.NewReader(bytes.NewReader(body), int64(len(body)))
	var doc string
	for _, f := range zr.File {
		if f.Name == "word/document.xml" {
			rc, _ := f.Open()
			b, _ := io.ReadAll(rc)
			rc.Close()
			doc = string(b)
		}
	}
	if !strings.Contains(doc, "ROOM PHOTOS") || !strings.Contains(doc, "Photo 1") || !strings.Contains(doc, "No findings") || strings.Contains(doc, "1.1  ") {
		t.Fatalf("room photos printed as a finding")
	}
}
