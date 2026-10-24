package api

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"
)

// Fixes from review/security-audit-0.7.0.md.

func loginBody(code, password, device string) map[string]any {
	return map[string]any{"employee_code": code, "password": password, "device": map[string]string{"id": device}}
}

// S2: made-up employee codes from one IP are cut off before any password hashing.
func TestLoginGatePerIP(t *testing.T) {
	h := setupWith(t, func(c *Config) { c.LoginPerMinute = 5 })
	statuses := map[int]int{}
	for i := 0; i < 8; i++ {
		statuses[h.do("POST", "/v1/auth/login", "", loginBody("RANDOM"+string(rune('A'+i)), "whatever-123", newUUID()), nil)]++
	}
	if statuses[401] != 5 || statuses[429] != 3 {
		t.Fatalf("statuses %v, want 5x401 then 3x429", statuses)
	}
}

// S3: strangers' failures lock the account for new places, but not for a phone the owner already uses.
func TestAccountLockoutSparesKnownDevice(t *testing.T) {
	h := setup(t)
	h.user("deva", "ADMIN")
	phone := newUUID()
	if st := h.do("POST", "/v1/auth/login", "", loginBody("DEVA", "password-123", phone), nil); st != 200 {
		t.Fatalf("first login: %d", st)
	}
	// 15+ failures "from other networks": the harness has one IP, so its per-(code, IP) counter is
	// cleared before each attempt, as a different network would have its own.
	for i := 0; i < perAccountAllowance+1; i++ {
		h.resetPerIP("DEVA")
		h.do("POST", "/v1/auth/login", "", loginBody("DEVA", "wrong-pass-1", newUUID()), nil)
	}
	h.resetPerIP("DEVA")
	if st := h.do("POST", "/v1/auth/login", "", loginBody("DEVA", "password-123", newUUID()), nil); st != 429 {
		t.Fatalf("new device should be locked out: %d", st)
	}
	if st := h.do("POST", "/v1/auth/login", "", loginBody("DEVA", "password-123", phone), nil); st != 200 {
		t.Fatalf("owner's known phone locked out: %d", st)
	}
}

// S4: a temporary password allows nothing but changing it (plus me / logout).
func TestMustChangePasswordEnforcedByServer(t *testing.T) {
	h := setup(t)
	if _, err := CreateUser(context.Background(), h.db, "TEMP", "Temp", "INSPECTOR", "temp-pass-123", true); err != nil {
		t.Fatal(err)
	}
	var out struct{ Token string }
	if st := h.do("POST", "/v1/auth/login", "", loginBody("TEMP", "temp-pass-123", newUUID()), &out); st != 200 {
		t.Fatalf("login: %d", st)
	}
	tok := out.Token
	var e struct{ Error struct{ Code string } }
	if st := h.do("POST", "/v1/sync/push", tok, map[string]any{"items": []any{}}, &e); st != 403 || e.Error.Code != "PASSWORD_CHANGE_REQUIRED" {
		t.Fatalf("push before change: %d %s", st, e.Error.Code)
	}
	if st := h.do("GET", "/v1/me", tok, nil, nil); st != 200 {
		t.Fatalf("me: %d", st)
	}
	if st := h.do("POST", "/v1/auth/password", tok, map[string]string{"current_password": "temp-pass-123", "new_password": "short-9ch"}, nil); st != 400 {
		t.Fatalf("9-char password accepted: %d", st)
	}
	if st := h.do("POST", "/v1/auth/password", tok, map[string]string{"current_password": "temp-pass-123", "new_password": "a-better-pass"}, nil); st != 204 {
		t.Fatalf("change: %d", st)
	}
	if st := h.do("POST", "/v1/sync/push", tok, map[string]any{"items": []any{}}, nil); st != 200 {
		t.Fatalf("push after change: %d", st)
	}
}

// S12: sessions end after 90 days however actively they are used.
func TestSessionAbsoluteMaxAge(t *testing.T) {
	h := setup(t)
	h.user("deva", "ADMIN")
	tok := h.login("DEVA")
	if _, err := h.db.Exec(t.Context(), `UPDATE sessions SET created_at = now() - interval '91 days'`); err != nil {
		t.Fatal(err)
	}
	if st := h.do("GET", "/v1/me", tok, nil, nil); st != 401 {
		t.Fatalf("91-day-old session still valid: %d", st)
	}
}

// S6: phone numbers and emails typed into notes never reach DeepSeek.
func TestAIRequestsScrubContactDetails(t *testing.T) {
	h, f := setupAI(t)
	h.user("ADM", "ADMIN")
	admin := h.login("ADM")
	h.do("PUT", "/v1/admin/ai/key", admin, map[string]string{"key": goodKey}, nil)
	f.content = `{"title":"t"}`
	h.do("POST", "/v1/ai/finding", admin, map[string]any{
		"room": "Hall", "site_note": "owner said call +91 98480 22338 or ravi.k@example.com; crack 1200 mm long",
	}, nil)
	sent, _ := json.Marshal(f.last)
	if strings.Contains(string(sent), "98480") || strings.Contains(string(sent), "example.com") {
		t.Fatalf("contact details sent: %s", sent)
	}
	if !strings.Contains(string(sent), "[phone]") || !strings.Contains(string(sent), "1200 mm") {
		t.Fatalf("scrub too aggressive or missing: %s", sent)
	}
}

// S7: an export link dies 30 minutes after its last use, and each use extends it.
func TestExportLinkSlidingExpiry(t *testing.T) {
	h := setup(t)
	h.user("deva", "ADMIN")
	admin := h.login("DEVA")
	h.seedPhoto(admin, newUUID(), "IN_PROGRESS", 0, []byte("photo-bytes-for-the-export-test"))
	var created struct{ Export exportInfo }
	if st := h.do("POST", "/v1/admin/exports", admin, map[string]any{}, &created); st != 200 {
		t.Fatalf("export: %d", st)
	}
	var link struct{ Path string }
	h.do("POST", fmt.Sprintf("/v1/admin/exports/%d/link", created.Export.ID), admin, map[string]any{}, &link)
	expiresIn := func() time.Duration {
		var d float64
		_ = h.db.QueryRow(t.Context(), `SELECT extract(epoch FROM download_token_expires - now()) FROM exports WHERE id = $1`, created.Export.ID).Scan(&d)
		return time.Duration(d * float64(time.Second))
	}
	if d := expiresIn(); d > 31*time.Minute || d < 29*time.Minute {
		t.Fatalf("new link lives %v", d)
	}
	_, _ = h.db.Exec(t.Context(), `UPDATE exports SET download_token_expires = now() + interval '1 minute' WHERE id = $1`, created.Export.ID)
	if resp, _ := h.get(link.Path, "", map[string]string{"Range": "bytes=0-9"}); resp.StatusCode != 206 {
		t.Fatalf("range download: %d", resp.StatusCode)
	}
	if d := expiresIn(); d < 29*time.Minute {
		t.Fatalf("use did not extend the link: %v", d)
	}
	_, _ = h.db.Exec(t.Context(), `UPDATE exports SET download_token_expires = now() - interval '1 second' WHERE id = $1`, created.Export.ID)
	if resp, _ := h.get(link.Path, "", nil); resp.StatusCode != 404 {
		t.Fatalf("expired link: %d", resp.StatusCode)
	}
}

func (h *harness) resetPerIP(code string) {
	h.t.Helper()
	srv := h.server // a different network would have its own per-(code, IP) counter
	srv.limiter.mu.Lock()
	for k := range srv.limiter.failures {
		if strings.HasPrefix(k, code+"|") {
			delete(srv.limiter.failures, k)
		}
	}
	srv.limiter.mu.Unlock()
}
