package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Integration tests against a real, disposable PostgreSQL (TEST_DATABASE_URL). See deploy/test.sh.

type harness struct {
	t       *testing.T
	srv     *httptest.Server
	db      *pgxpool.Pool
	dataDir string
	server  *Server
}

func setup(t *testing.T) *harness { return setupWith(t, nil) }

func setupWith(t *testing.T, configure func(*Config)) *harness {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	db, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(ctx, `DROP SCHEMA public CASCADE; CREATE SCHEMA public;`)
	if err != nil {
		t.Fatal(err)
	}
	if err := Migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	dataDir := t.TempDir()
	cfg := Config{DataDir: dataDir, MaxUploadBytes: 10 << 20, SessionTTL: time.Hour, LoginPerMinute: 10_000}
	if configure != nil {
		configure(&cfg)
	}
	s := New(db, cfg, slog.New(slog.NewTextHandler(io.Discard, nil)))
	s.lockTTL = 0 // tests flip the developer lock and expect the next request to see it
	h := &harness{t: t, srv: httptest.NewServer(s.Handler()), db: db, dataDir: dataDir, server: s}
	t.Cleanup(func() { h.srv.Close(); db.Close() })
	return h
}

func (h *harness) do(method, path, token string, body any, out any) int {
	h.t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case []byte:
		rd = bytes.NewReader(b)
	default:
		raw, _ := json.Marshal(b)
		rd = bytes.NewReader(raw)
	}
	req, _ := http.NewRequest(method, h.srv.URL+path, rd)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		_ = json.NewDecoder(resp.Body).Decode(out)
	}
	return resp.StatusCode
}

func (h *harness) user(code, role string) {
	h.t.Helper()
	if _, err := CreateUser(context.Background(), h.db, code, "Name "+code, role, "password-123", false); err != nil {
		h.t.Fatal(err)
	}
}

func (h *harness) login(code string) string {
	h.t.Helper()
	var out struct{ Token string }
	st := h.do("POST", "/v1/auth/login", "", map[string]any{
		"employee_code": code, "password": "password-123", "device": map[string]string{"id": newUUID(), "label": "test"},
	}, &out)
	if st != 200 || out.Token == "" {
		h.t.Fatalf("login %s: %d", code, st)
	}
	return out.Token
}

type results struct {
	Results []pushResult `json:"results"`
}

func (h *harness) push(token string, items ...map[string]any) []pushResult {
	h.t.Helper()
	var out results
	if st := h.do("POST", "/v1/sync/push", token, map[string]any{"items": items}, &out); st != 200 {
		h.t.Fatalf("push status %d", st)
	}
	return out.Results
}

func inspection(id string, base int64, mutation, client string) map[string]any {
	return map[string]any{
		"type": "INSPECTION", "id": id, "base_revision": base, "mutation_id": mutation,
		"data": map[string]any{
			"client_name": client, "client_phone": "", "property_address": "Guntur", "inspector_user_id": "x",
			"inspection_date": "2026-10-20", "time_zone": "Asia/Kolkata", "status": "IN_PROGRESS", "report_version": 0,
			"created_at": 1, "updated_at": 2,
		},
	}
}

func TestLoginAndSessionRules(t *testing.T) {
	h := setup(t)
	h.user("deva", "ADMIN")
	h.user("insp", "INSPECTOR")

	if st := h.do("POST", "/v1/auth/login", "", map[string]any{"employee_code": "DEVA", "password": "nope-nope", "device": map[string]string{"id": newUUID()}}, nil); st != 401 {
		t.Fatalf("bad password: %d", st)
	}
	admin := h.login("Deva") // codes are case-insensitive
	insp := h.login("INSP")
	if st := h.do("GET", "/v1/me", insp, nil, nil); st != 200 {
		t.Fatalf("me: %d", st)
	}
	if st := h.do("GET", "/v1/admin/users", insp, nil, nil); st != 403 {
		t.Fatalf("inspector reached admin: %d", st)
	}
	var list struct{ Users []userRow }
	h.do("GET", "/v1/admin/users", admin, nil, &list)
	if len(list.Users) != 2 {
		t.Fatalf("users: %+v", list)
	}
	var inspID string
	for _, u := range list.Users {
		if u.EmployeeCode == "INSP" {
			inspID = u.ID
		}
	}
	// Disabling revokes sessions immediately (PRD §5.1 / §10).
	if st := h.do("PATCH", "/v1/admin/users/"+inspID, admin, map[string]any{"status": "DISABLED"}, nil); st != 204 {
		t.Fatalf("disable: %d", st)
	}
	var e struct{ Error struct{ Code string } }
	if st := h.do("GET", "/v1/me", insp, nil, &e); st != 401 || e.Error.Code != "SESSION_INVALID" {
		t.Fatalf("disabled user still in: %d %s", st, e.Error.Code)
	}
	if st := h.do("POST", "/v1/auth/login", "", map[string]any{"employee_code": "INSP", "password": "password-123", "device": map[string]string{"id": newUUID()}}, nil); st != 403 {
		t.Fatalf("disabled login: %d", st)
	}
	if st := h.do("POST", "/v1/auth/logout", admin, nil, nil); st != 204 {
		t.Fatalf("logout: %d", st)
	}
	if st := h.do("GET", "/v1/me", admin, nil, nil); st != 401 {
		t.Fatalf("after logout: %d", st)
	}
}

func TestPushIdempotencyAndConflicts(t *testing.T) {
	h := setup(t)
	h.user("a", "INSPECTOR")
	tok := h.login("A")
	id := newUUID()

	r := h.push(tok, inspection(id, 0, "k1:1", "Priya"))
	if r[0].Status != "OK" || r[0].Revision != 1 {
		t.Fatalf("insert: %+v", r[0])
	}
	// The same mutation retried (response lost) is acknowledged, not applied twice.
	r = h.push(tok, inspection(id, 0, "k1:1", "Priya"))
	if r[0].Status != "OK" || r[0].Revision != 1 {
		t.Fatalf("retry: %+v", r[0])
	}
	r = h.push(tok, inspection(id, 1, "k1:2", "Priya R"))
	if r[0].Status != "OK" || r[0].Revision != 2 {
		t.Fatalf("update: %+v", r[0])
	}
	// Another device still on revision 1 must not overwrite revision 2.
	r = h.push(tok, inspection(id, 1, "k9:1", "Stale"))
	if r[0].Status != "CONFLICT" || r[0].Server["client_name"] != "Priya R" {
		t.Fatalf("conflict: %+v", r[0])
	}
	// Unless the user chose "keep mine".
	force := inspection(id, 1, "k9:2", "Mine")
	force["force"] = true
	if r = h.push(tok, force); r[0].Status != "OK" || r[0].Revision != 3 {
		t.Fatalf("force: %+v", r[0])
	}
	// Child before parent: retried later, not rejected.
	room := map[string]any{"type": "ROOM", "id": newUUID(), "base_revision": 0, "mutation_id": "r:1", "data": map[string]any{
		"inspection_id": newUUID(), "name": "Kitchen", "sort_order": 0, "is_custom": false, "created_at": 1, "updated_at": 1}}
	if r = h.push(tok, room); r[0].Status != "RETRY" || r[0].Code != "PARENT_MISSING" {
		t.Fatalf("orphan: %+v", r[0])
	}
	bad := inspection(newUUID(), 0, "b:1", "x")
	delete(bad["data"].(map[string]any), "client_name")
	if r = h.push(tok, bad); r[0].Status != "REJECTED" {
		t.Fatalf("validation: %+v", r[0])
	}
}

func TestPhotoUploadVerification(t *testing.T) {
	h := setup(t)
	h.user("a", "INSPECTOR")
	tok := h.login("A")
	insp, room, finding, photo := newUUID(), newUUID(), newUUID(), newUUID()
	bytesJPEG := bytes.Repeat([]byte("jpegdata"), 5000)
	sum := sha256.Sum256(bytesJPEG)
	hash := hex.EncodeToString(sum[:])
	r := h.push(tok,
		inspection(insp, 0, "i:1", "C"),
		map[string]any{"type": "ROOM", "id": room, "base_revision": 0, "mutation_id": "r:1", "data": map[string]any{
			"inspection_id": insp, "name": "Kitchen", "sort_order": 0, "is_custom": false, "created_at": 1, "updated_at": 1}},
		map[string]any{"type": "FINDING", "id": finding, "base_revision": 0, "mutation_id": "f:1", "data": map[string]any{
			"inspection_id": insp, "room_id": room, "rough_note": "crack", "status": "OPEN", "sort_order": 0, "created_by": "u",
			"created_at": 1, "updated_at": 1}},
		map[string]any{"type": "PHOTO", "id": photo, "base_revision": 0, "mutation_id": "p:1", "data": map[string]any{
			"inspection_id": insp, "room_id": room, "finding_id": finding, "mime_type": "image/jpeg", "width": 4000, "height": 3000,
			"file_size_bytes": len(bytesJPEG), "sha256": hash, "captured_at": 1, "created_at": 1, "updated_at": 1}},
	)
	for _, x := range r {
		if x.Status != "OK" {
			t.Fatalf("push: %+v", x)
		}
	}
	// Corrupted bytes of the right length are refused.
	corrupt := append([]byte{}, bytesJPEG...)
	corrupt[10] ^= 1
	if st := h.do("PUT", "/v1/photos/"+photo+"/original", tok, corrupt, nil); st != 422 {
		t.Fatalf("corrupt upload: %d", st)
	}
	var ok struct {
		ObjectKey  string `json:"object_key"`
		SHA256     string `json:"sha256"`
		VerifiedAt int64  `json:"verified_at"`
	}
	if st := h.do("PUT", "/v1/photos/"+photo+"/original", tok, bytesJPEG, &ok); st != 200 || ok.SHA256 != hash || ok.VerifiedAt == 0 {
		t.Fatalf("upload: %d %+v", st, ok)
	}
	// Retry after a lost response: same answer, nothing rewritten.
	if st := h.do("PUT", "/v1/photos/"+photo+"/original", tok, bytesJPEG, &ok); st != 200 || ok.SHA256 != hash {
		t.Fatalf("re-upload: %d", st)
	}
	// Download returns identical bytes; Range works for resuming.
	req, _ := http.NewRequest("GET", h.srv.URL+"/v1/photos/"+photo+"/original", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Range", "bytes=8-")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 206 || !bytes.Equal(got, bytesJPEG[8:]) {
		t.Fatalf("download: %d len=%d", resp.StatusCode, len(got))
	}
	// The photo's checksum is immutable.
	r = h.push(tok, map[string]any{"type": "PHOTO", "id": photo, "base_revision": 1, "mutation_id": "p:2", "data": map[string]any{
		"inspection_id": insp, "room_id": room, "finding_id": finding, "mime_type": "image/jpeg", "width": 4000, "height": 3000,
		"file_size_bytes": len(bytesJPEG), "sha256": "00", "captured_at": 1, "created_at": 1, "updated_at": 2}})
	if r[0].Status != "REJECTED" || r[0].Code != "IMMUTABLE_FIELD" {
		t.Fatalf("sha changed: %+v", r[0])
	}
}

func TestChangesPaging(t *testing.T) {
	h := setup(t)
	h.user("a", "INSPECTOR")
	tok := h.login("A")
	var items []map[string]any
	for i := 0; i < 7; i++ {
		items = append(items, inspection(newUUID(), 0, fmt.Sprintf("m%d:1", i), fmt.Sprintf("C%d", i)))
	}
	h.push(tok, items...)
	seen := map[string]bool{}
	cursor := int64(0)
	for page := 0; page < 10; page++ {
		var out struct {
			Changes []struct {
				Type string         `json:"type"`
				Data map[string]any `json:"data"`
			} `json:"changes"`
			Cursor  int64 `json:"cursor"`
			HasMore bool  `json:"has_more"`
		}
		h.do("GET", fmt.Sprintf("/v1/sync/changes?cursor=%d&limit=3", cursor), tok, nil, &out)
		for _, c := range out.Changes {
			seen[c.Type+":"+c.Data["id"].(string)] = true
			if c.Type == "USER" && c.Data["password_hash"] != nil {
				t.Fatal("password hash leaked")
			}
		}
		cursor = out.Cursor
		if !out.HasMore {
			break
		}
	}
	if len(seen) != 8 { // 7 inspections + 1 user
		t.Fatalf("saw %d records: %v", len(seen), seen)
	}
}

// Room common photos (0.9): a finding's kind survives a push from an older app, which never sends it,
// and an older app can still create findings (kind stays NULL = an issue).
func TestFindingKindKeptWhenPushOmitsIt(t *testing.T) {
	h := setup(t)
	h.user("a", "INSPECTOR")
	tok := h.login("A")
	insp, room, common, old := newUUID(), newUUID(), newUUID(), newUUID()
	finding := func(id string, base int64, mutation, note string) map[string]any {
		return map[string]any{"type": "FINDING", "id": id, "base_revision": base, "mutation_id": mutation, "data": map[string]any{
			"inspection_id": insp, "room_id": room, "rough_note": note, "status": "OPEN", "sort_order": 0, "created_by": "u",
			"created_at": 1, "updated_at": 1}}
	}
	withKind := finding(common, 0, "c:1", "")
	withKind["data"].(map[string]any)["kind"] = "ROOM_PHOTOS"
	r := h.push(tok,
		inspection(insp, 0, "i:1", "C"),
		map[string]any{"type": "ROOM", "id": room, "base_revision": 0, "mutation_id": "r:1", "data": map[string]any{
			"inspection_id": insp, "name": "Kitchen", "sort_order": 0, "is_custom": false, "created_at": 1, "updated_at": 1}},
		withKind,
		finding(old, 0, "o:1", "crack"), // older app: no kind key
	)
	for _, x := range r {
		if x.Status != "OK" {
			t.Fatalf("push: %+v", x)
		}
	}
	if r := h.push(tok, finding(common, 1, "c:2", "moved")); r[0].Status != "OK" { // older app editing it
		t.Fatalf("old edit: %+v", r[0])
	}
	var kind *string
	var note string
	_ = h.db.QueryRow(t.Context(), `SELECT kind, rough_note FROM findings WHERE id = $1`, common).Scan(&kind, &note)
	if kind == nil || *kind != "ROOM_PHOTOS" || note != "moved" {
		t.Fatalf("after old edit: %v %q", kind, note)
	}
	_ = h.db.QueryRow(t.Context(), `SELECT kind FROM findings WHERE id = $1`, old).Scan(&kind)
	if kind != nil {
		t.Fatalf("old insert got a kind: %v", *kind)
	}
}
