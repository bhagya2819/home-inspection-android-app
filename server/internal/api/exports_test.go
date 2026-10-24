package api

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/csv"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"
)

// seedPhoto pushes inspection → room → finding → photo and uploads the photo's bytes.
func (h *harness) seedPhoto(tok, insp string, status string, finalizedAt int64, content []byte) string {
	h.t.Helper()
	room, finding, photo := newUUID(), newUUID(), newUUID()
	sum := sha256.Sum256(content)
	in := inspection(insp, 0, "i:"+insp, "Client "+insp[:4])
	data := in["data"].(map[string]any)
	data["status"] = status
	if finalizedAt > 0 {
		data["finalized_at"] = finalizedAt
	}
	r := h.push(tok, in,
		map[string]any{"type": "ROOM", "id": room, "base_revision": 0, "mutation_id": "r:" + room, "data": map[string]any{
			"inspection_id": insp, "name": "Kitchen", "sort_order": 0, "is_custom": false, "created_at": 1, "updated_at": 1}},
		map[string]any{"type": "FINDING", "id": finding, "base_revision": 0, "mutation_id": "f:" + finding, "data": map[string]any{
			"inspection_id": insp, "room_id": room, "rough_note": "crack", "status": "OPEN", "sort_order": 0, "created_by": "u",
			"created_at": 1, "updated_at": 1}},
		map[string]any{"type": "PHOTO", "id": photo, "base_revision": 0, "mutation_id": "p:" + photo, "data": map[string]any{
			"inspection_id": insp, "room_id": room, "finding_id": finding, "mime_type": "image/jpeg", "width": 4000, "height": 3000,
			"file_size_bytes": len(content), "sha256": hex.EncodeToString(sum[:]), "captured_at": 1, "created_at": 1, "updated_at": 1}},
	)
	for _, x := range r {
		if x.Status != "OK" && !(x.Type == "INSPECTION" && x.Status == "CONFLICT") {
			h.t.Fatalf("seed push: %+v", x)
		}
	}
	if st := h.do("PUT", "/v1/photos/"+photo+"/original", tok, content, nil); st != 200 {
		h.t.Fatalf("seed upload: %d", st)
	}
	return photo
}

func (h *harness) get(path, token string, headers map[string]string) (*http.Response, []byte) {
	h.t.Helper()
	req, _ := http.NewRequest("GET", h.srv.URL+path, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return resp, body
}

func TestExportVerifyArchive(t *testing.T) {
	h := setup(t)
	h.user("boss", "ADMIN")
	h.user("insp", "INSPECTOR")
	admin := h.login("BOSS")
	insp := h.login("INSP")

	old := time.Now().Add(-90 * 24 * time.Hour).UnixMilli()
	oldInspection, newInspection := newUUID(), newUUID()
	jpegA := bytes.Repeat([]byte("A-photo-"), 40_000)
	jpegB := bytes.Repeat([]byte("B-photo-"), 30_000)
	photoOld := h.seedPhoto(insp, oldInspection, "FINALIZED", old, jpegA)
	photoNew := h.seedPhoto(insp, newInspection, "IN_PROGRESS", 0, jpegB)

	// Inspectors can't reach admin endpoints.
	if st := h.do("GET", "/v1/admin/storage", insp, nil, nil); st != 403 {
		t.Fatalf("inspector storage: %d", st)
	}
	var storage struct {
		Unexported int64 `json:"unexported_bytes"`
		Free       int64 `json:"data_free_bytes"`
	}
	h.do("GET", "/v1/admin/storage", admin, nil, &storage)
	if storage.Unexported != int64(len(jpegA)+len(jpegB)) || storage.Free <= 0 {
		t.Fatalf("storage: %+v", storage)
	}

	var created struct {
		Export exportInfo `json:"export"`
	}
	if st := h.do("POST", "/v1/admin/exports", admin, map[string]any{}, &created); st != 200 || created.Export.EntryCount != 4 {
		t.Fatalf("create export: %d %+v", st, created.Export) // manifest + data.json + 2 photos (same month)
	}
	ex := created.Export
	// A second request returns the same open export instead of an overlapping one.
	var again struct {
		Export exportInfo `json:"export"`
	}
	h.do("POST", "/v1/admin/exports", admin, map[string]any{}, &again)
	if again.Export.ID != ex.ID {
		t.Fatalf("second export %d != %d", again.Export.ID, ex.ID)
	}

	// Archive is refused before verification.
	if st := h.do("POST", fmt.Sprintf("/v1/admin/exports/%d/archive", ex.ID), admin, map[string]any{}, nil); st != 409 {
		t.Fatalf("archive unverified: %d", st)
	}

	// Download through a scoped link (no bearer token), as the phone's DownloadManager does.
	var link struct {
		Path string `json:"path"`
	}
	h.do("POST", fmt.Sprintf("/v1/admin/exports/%d/link", ex.ID), admin, map[string]any{}, &link)
	resp, zipBytes := h.get(link.Path, "", nil)
	if resp.StatusCode != 200 || int64(len(zipBytes)) != ex.ZipBytes || resp.ContentLength != ex.ZipBytes {
		t.Fatalf("zip download: %d len=%d want %d", resp.StatusCode, len(zipBytes), ex.ZipBytes)
	}
	// Deterministic bytes: a resumed range equals the tail of the full download.
	resp, tail := h.get(fmt.Sprintf("/v1/admin/exports/%d/zip", ex.ID), admin, map[string]string{"Range": "bytes=1000-"})
	if resp.StatusCode != 206 || !bytes.Equal(tail, zipBytes[1000:]) {
		t.Fatalf("range: %d", resp.StatusCode)
	}
	if st := h.do("GET", "/v1/export-files/not-a-token", "", nil, nil); st != 404 {
		t.Fatalf("bad link: %d", st)
	}

	// Verify like the app: every entry's SHA-256 against manifest.csv, the manifest against the server.
	zr, err := zip.NewReader(bytes.NewReader(zipBytes), int64(len(zipBytes)))
	if err != nil {
		t.Fatal(err)
	}
	hashes := map[string]string{}
	var manifest []byte
	for _, f := range zr.File {
		if f.Method != zip.Store {
			t.Fatalf("%s not stored", f.Name)
		}
		rc, _ := f.Open()
		b, err := io.ReadAll(rc) // archive/zip checks each entry's CRC-32 here
		rc.Close()
		if err != nil {
			t.Fatalf("%s: %v", f.Name, err)
		}
		sum := sha256.Sum256(b)
		hashes[f.Name] = hex.EncodeToString(sum[:])
		if f.Name == "manifest.csv" {
			manifest = b
		}
	}
	rows, _ := csv.NewReader(bytes.NewReader(manifest)).ReadAll()
	if len(rows) != 4 { // header + data.json + 2 photos
		t.Fatalf("manifest rows: %d", len(rows))
	}
	for _, row := range rows[1:] {
		if hashes[row[0]] != row[1] {
			t.Fatalf("hash mismatch for %s", row[0])
		}
	}
	if hashes["manifest.csv"] != ex.ManifestSHA256 {
		t.Fatal("manifest sha differs")
	}
	if !strings.Contains(string(mustRead(t, zr, "data.json")), photoOld) {
		t.Fatal("data.json lacks the photo record")
	}

	if st := h.do("POST", fmt.Sprintf("/v1/admin/exports/%d/verified", ex.ID), admin,
		map[string]any{"manifest_sha256": "00", "entries_verified": 4}, nil); st != 409 {
		t.Fatalf("wrong manifest accepted: %d", st)
	}
	if st := h.do("POST", fmt.Sprintf("/v1/admin/exports/%d/verified", ex.ID), admin,
		map[string]any{"manifest_sha256": ex.ManifestSHA256, "entries_verified": 4}, nil); st != 200 {
		t.Fatalf("verified: %d", st)
	}
	// Phones learn that the photos are in a verified export through the normal pull.
	if exportedAt := pulledField(h, insp, photoOld, "export_verified_at"); exportedAt == nil {
		t.Fatal("export_verified_at not pulled")
	}
	var nothing struct{}
	if st := h.do("POST", "/v1/admin/exports", admin, map[string]any{}, &nothing); st != 409 {
		t.Fatalf("everything exported, still created: %d", st)
	}

	// Archive: only the finalized inspection older than 60 days loses its server file.
	var arch struct {
		ArchivedNow  int   `json:"archived_now"`
		KeptNotFinal int   `json:"kept_not_finalized"`
		Freed        int64 `json:"freed_bytes"`
	}
	if st := h.do("POST", fmt.Sprintf("/v1/admin/exports/%d/archive", ex.ID), admin, map[string]any{}, &arch); st != 200 ||
		arch.ArchivedNow != 1 || arch.KeptNotFinal != 1 || arch.Freed != int64(len(jpegA)) {
		t.Fatalf("archive: %d %+v", st, arch)
	}
	resp, body := h.get("/v1/photos/"+photoOld+"/original", insp, nil)
	if resp.StatusCode != 410 || !strings.Contains(string(body), "ARCHIVED") {
		t.Fatalf("archived photo: %d %s", resp.StatusCode, body)
	}
	if resp, _ := h.get("/v1/photos/"+photoNew+"/original", insp, nil); resp.StatusCode != 200 {
		t.Fatalf("kept photo: %d", resp.StatusCode)
	}
	if pulledField(h, insp, photoOld, "archived_at") == nil {
		t.Fatal("archived_at not pulled")
	}
	// The export can't be served any more; a repeat archive is harmless.
	if resp, _ := h.get(link.Path, "", nil); resp.StatusCode != 410 {
		t.Fatalf("archived zip: %d", resp.StatusCode)
	}
	if st := h.do("POST", fmt.Sprintf("/v1/admin/exports/%d/archive", ex.ID), admin, map[string]any{}, &arch); st != 200 || arch.ArchivedNow != 0 {
		t.Fatalf("repeat archive: %d %+v", st, arch)
	}

	// What a phone asks right before retention cleanup.
	var check struct {
		Results map[string]struct{ Status string } `json:"results"`
	}
	unknown := newUUID()
	h.do("POST", "/v1/photos/check", insp, map[string]any{"ids": []string{photoOld, photoNew, unknown}}, &check)
	if check.Results[photoOld].Status != "ARCHIVED" || check.Results[photoNew].Status != "OK" || check.Results[unknown].Status != "MISSING" {
		t.Fatalf("check: %+v", check.Results)
	}
	// PRD §16.4: a verified copy that later disappears is reported, so the phone keeps its original.
	var key string
	if err := h.db.QueryRow(t.Context(), `SELECT object_key FROM photos WHERE id = $1`, photoNew).Scan(&key); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(h.dataDir + "/" + key); err != nil {
		t.Fatal(err)
	}
	h.do("POST", "/v1/photos/check", insp, map[string]any{"ids": []string{photoNew}}, &check)
	if check.Results[photoNew].Status != "MISSING" {
		t.Fatalf("lost copy: %+v", check.Results)
	}
}

func TestExportSkipsCorruptObjects(t *testing.T) {
	h := setup(t)
	h.user("boss", "ADMIN")
	admin := h.login("BOSS")
	good := h.seedPhoto(admin, newUUID(), "IN_PROGRESS", 0, bytes.Repeat([]byte("good"), 1000))
	bad := h.seedPhoto(admin, newUUID(), "IN_PROGRESS", 0, bytes.Repeat([]byte("bad!"), 1000))
	var key string
	if err := h.db.QueryRow(t.Context(), `SELECT object_key FROM photos WHERE id = $1`, bad).Scan(&key); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(h.dataDir+"/"+key, bytes.Repeat([]byte("BAD!"), 1000), 0o640); err != nil {
		t.Fatal(err)
	}
	var out struct {
		Export   exportInfo      `json:"export"`
		Problems []objectProblem `json:"problems"`
	}
	if st := h.do("POST", "/v1/admin/exports", admin, map[string]any{}, &out); st != 200 {
		t.Fatalf("create: %d", st)
	}
	if out.Export.EntryCount != 3 || len(out.Problems) != 1 || out.Problems[0].ID != bad || out.Problems[0].Problem != "CHECKSUM_MISMATCH" {
		t.Fatalf("corrupt object handling: %+v %+v (good=%s)", out.Export, out.Problems, good)
	}
}

func TestSettingsDevicesAudit(t *testing.T) {
	h := setup(t)
	h.user("boss", "ADMIN")
	h.user("insp", "INSPECTOR")
	admin := h.login("BOSS")
	insp := h.login("INSP")

	var settings struct {
		Retention int    `json:"retention_days"`
		Policy    string `json:"policy_version"`
	}
	h.do("GET", "/v1/settings", insp, nil, &settings)
	if settings.Retention != 30 || settings.Policy != "retention-30d" {
		t.Fatalf("default settings: %+v", settings)
	}
	if st := h.do("PATCH", "/v1/admin/settings", insp, map[string]int{"retention_days": 45}, nil); st != 403 {
		t.Fatalf("inspector changed settings: %d", st)
	}
	if st := h.do("PATCH", "/v1/admin/settings", admin, map[string]int{"retention_days": 2}, nil); st != 400 {
		t.Fatalf("out of range accepted: %d", st)
	}
	h.do("PATCH", "/v1/admin/settings", admin, map[string]int{"retention_days": 45}, &settings)
	if settings.Retention != 45 {
		t.Fatalf("settings not saved: %+v", settings)
	}

	eventID := newUUID()
	report := map[string]any{
		"app_version": "0.5.0", "pending_records": 2, "pending_photos": 1,
		"problems": []map[string]any{{"type": "PHOTO", "id": "x", "op": "UPLOAD_ORIGINAL", "code": "NETWORK", "message": "timeout", "attempts": 3}},
		"audit":    []map[string]any{{"id": eventID, "type": "PHOTO_ORIGINAL_DELETED", "entity_type": "PHOTO", "entity_id": "p1", "details": "policy=retention-30d", "created_at": 5}},
	}
	var accepted struct {
		Accepted []string `json:"accepted"`
	}
	for i := 0; i < 2; i++ { // a resend after a lost response is stored once
		if st := h.do("POST", "/v1/devices/report", insp, report, &accepted); st != 200 || len(accepted.Accepted) != 1 {
			t.Fatalf("report: %d %+v", st, accepted)
		}
	}
	var devices struct {
		Devices []struct {
			ID     string         `json:"id"`
			Code   string         `json:"employee_code"`
			Report map[string]any `json:"sync_report"`
		} `json:"devices"`
	}
	h.do("GET", "/v1/admin/devices", admin, nil, &devices)
	var inspDevice string
	for _, d := range devices.Devices {
		if d.Code == "INSP" {
			inspDevice = d.ID
			if d.Report["pending_photos"] != float64(1) || len(d.Report["problems"].([]any)) != 1 {
				t.Fatalf("device report: %+v", d.Report)
			}
		}
	}
	var audit struct {
		Events []struct {
			Type       string `json:"type"`
			FromDevice bool   `json:"from_device"`
		} `json:"events"`
	}
	h.do("GET", "/v1/admin/audit?type=PHOTO_ORIGINAL_DELETED", admin, nil, &audit)
	if len(audit.Events) != 1 || !audit.Events[0].FromDevice {
		t.Fatalf("device audit: %+v", audit)
	}

	// Blocking a phone ends its session immediately.
	if st := h.do("PATCH", "/v1/admin/devices/"+inspDevice, admin, map[string]bool{"revoked": true}, nil); st != 204 {
		t.Fatalf("block device: %d", st)
	}
	if st := h.do("GET", "/v1/me", insp, nil, nil); st != 401 {
		t.Fatalf("blocked device still signed in: %d", st)
	}

	var list struct {
		Inspections []map[string]any `json:"inspections"`
	}
	if st := h.do("GET", "/v1/admin/inspections", admin, nil, &list); st != 200 {
		t.Fatalf("admin inspections: %d", st)
	}
}

func mustRead(t *testing.T, zr *zip.Reader, name string) []byte {
	f, err := zr.Open(name)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	b, _ := io.ReadAll(f)
	return b
}

// pulledField returns a server field of a photo as the phones' pull delivers it.
func pulledField(h *harness, token, photoID, field string) any {
	var out struct {
		Changes []struct {
			Type string         `json:"type"`
			Data map[string]any `json:"data"`
		} `json:"changes"`
	}
	h.do("GET", "/v1/sync/changes?cursor=0&limit=1000", token, nil, &out)
	for _, c := range out.Changes {
		if c.Type == "PHOTO" && c.Data["id"] == photoID {
			return c.Data[field]
		}
	}
	return nil
}
