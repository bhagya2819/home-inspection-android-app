package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fakeDeepSeek answers /models (key check) and /chat/completions with a canned JSON content.
type fakeDeepSeek struct {
	mu      sync.Mutex
	content string
	status  int
	delay   time.Duration
	calls   int
	last    map[string]any // last chat request body
	srv     *httptest.Server
}

const goodKey = "sk-test-good-key-0123456789abcd"

func newFakeDeepSeek(t *testing.T) *fakeDeepSeek {
	f := &fakeDeepSeek{status: 200}
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+goodKey {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.URL.Path == "/models" {
			_, _ = io.WriteString(w, `{"object":"list","data":[]}`)
			return
		}
		f.mu.Lock()
		delay := f.delay
		f.calls++
		f.mu.Unlock()
		time.Sleep(delay) // outside the lock, so parallel requests really overlap
		f.mu.Lock()
		defer f.mu.Unlock()
		_ = json.NewDecoder(r.Body).Decode(&f.last)
		if f.status != 200 {
			w.WriteHeader(f.status)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]any{"role": "assistant", "content": f.content}}},
			"usage":   map[string]any{"prompt_tokens": 100, "completion_tokens": 50},
		})
	}))
	t.Cleanup(f.srv.Close)
	return f
}

func setupAI(t *testing.T) (*harness, *fakeDeepSeek) {
	f := newFakeDeepSeek(t)
	h := setupWith(t, func(c *Config) {
		c.AISecret = []byte("0123456789abcdef0123456789abcdef")
		c.DeepSeekURL = f.srv.URL
	})
	return h, f
}

func TestAIKeyIsWriteOnly(t *testing.T) {
	h, _ := setupAI(t)
	h.user("ADM", "ADMIN")
	h.user("INS", "INSPECTOR")
	admin, insp := h.login("ADM"), h.login("INS")

	var settings map[string]any
	h.do("GET", "/v1/settings", insp, nil, &settings)
	if settings["ai_enabled"] != false {
		t.Fatalf("ai_enabled before a key: %v", settings["ai_enabled"])
	}
	if st := h.do("PUT", "/v1/admin/ai/key", insp, map[string]string{"key": goodKey}, nil); st != 403 {
		t.Fatalf("inspector set key: %d", st)
	}
	if st := h.do("PUT", "/v1/admin/ai/key", admin, map[string]string{"key": "sk-wrong-key-0123456789abcdef"}, nil); st != 400 {
		t.Fatalf("rejected key: %d", st)
	}
	var info map[string]any
	if st := h.do("PUT", "/v1/admin/ai/key", admin, map[string]string{"key": " " + goodKey + "\n"}, &info); st != 200 {
		t.Fatalf("set key: %d", st)
	}
	raw, _ := json.Marshal(info)
	if strings.Contains(string(raw), "good-key") || info["key_last4"] != "abcd" || info["key_set"] != true {
		t.Fatalf("admin view: %s", raw)
	}
	var stored []byte
	_ = h.db.QueryRow(t.Context(), `SELECT ciphertext FROM ai_key`).Scan(&stored)
	if strings.Contains(string(stored), "good-key") {
		t.Fatal("key stored in plain text")
	}
	h.do("GET", "/v1/settings", insp, nil, &settings)
	if settings["ai_enabled"] != true {
		t.Fatalf("ai_enabled after a key: %v", settings["ai_enabled"])
	}
	if st := h.do("DELETE", "/v1/admin/ai/key", admin, nil, &info); st != 200 || info["key_set"] != false {
		t.Fatalf("remove: %d %v", st, info)
	}
	var out map[string]any
	if st := h.do("POST", "/v1/ai/finding", insp, map[string]any{"room": "Kitchen", "site_note": "crack"}, &out); st != 503 {
		t.Fatalf("finding without key: %d %v", st, out)
	}
}

func TestAIFindingSuggestionAndLimit(t *testing.T) {
	h, f := setupAI(t)
	h.user("ADM", "ADMIN")
	admin := h.login("ADM")
	h.do("PUT", "/v1/admin/ai/key", admin, map[string]string{"key": goodKey}, nil)
	f.content = `{"title":"Hairline crack above window","observation":"A hairline crack — about the width of a hair.","implication":"x","recommendation":"y","severity":"MINOR","severity_reason":"cosmetic","category":"walls & finishes"}`

	var out map[string]any
	st := h.do("POST", "/v1/ai/finding", admin, map[string]any{
		"room": "Bedroom 1", "site_note": "crack near window, chinna crack", "categories": []string{"Structural", "Walls & finishes"},
		"client_name": "must not be forwarded",
	}, &out)
	if st != 200 {
		t.Fatalf("finding: %d %v", st, out)
	}
	if out["title"] != "Hairline crack above window" || out["severity"] != "MINOR" || out["category"] != "Walls & finishes" {
		t.Fatalf("suggestion: %v", out)
	}
	if !strings.Contains(out["observation"].(string), "crack - about") {
		t.Fatalf("dash not made ASCII: %q", out["observation"])
	}
	sent, _ := json.Marshal(f.last)
	if !strings.Contains(string(sent), "chinna crack") || strings.Contains(string(sent), "must not be forwarded") {
		t.Fatalf("request to DeepSeek: %s", sent)
	}
	if f.last["response_format"] == nil || f.last["model"] != "deepseek-flash" {
		t.Fatalf("request params: %v", f.last)
	}

	// Unknown severity/category from the model are dropped, not passed on.
	f.content = `{"title":"t","observation":"o","implication":"i","recommendation":"r","severity":"CRITICAL","category":"Garden"}`
	out = nil
	h.do("POST", "/v1/ai/finding", admin, map[string]any{"room": "Hall", "site_note": "x", "categories": []string{"Other"}}, &out)
	if _, ok := out["severity"]; ok {
		t.Fatalf("invalid severity passed: %v", out)
	}
	if _, ok := out["category"]; ok {
		t.Fatalf("invalid category passed: %v", out)
	}

	// Daily limit: 2 used so far today.
	if st := h.do("PATCH", "/v1/admin/settings", admin, map[string]int{"ai_daily_limit": 2}, nil); st != 200 {
		t.Fatalf("set limit: %d", st)
	}
	out = nil
	if st := h.do("POST", "/v1/ai/finding", admin, map[string]any{"room": "Hall", "site_note": "x"}, &out); st != 429 {
		t.Fatalf("over limit: %d %v", st, out)
	}
	var info map[string]any
	h.do("GET", "/v1/admin/ai", admin, nil, &info)
	if info["month_requests"] != float64(2) {
		t.Fatalf("usage: %v", info)
	}
}

func TestAIDeepSeekErrorsAreFriendly(t *testing.T) {
	h, f := setupAI(t)
	h.user("ADM", "ADMIN")
	admin := h.login("ADM")
	h.do("PUT", "/v1/admin/ai/key", admin, map[string]string{"key": goodKey}, nil)
	f.status = http.StatusPaymentRequired
	var out struct{ Error struct{ Code string } }
	if st := h.do("POST", "/v1/ai/finding", admin, map[string]any{"room": "Hall", "site_note": "x"}, &out); st != 502 || out.Error.Code != "AI_NO_BALANCE" {
		t.Fatalf("402: %d %v", st, out)
	}
	f.status, f.content = 200, `not json`
	if st := h.do("POST", "/v1/ai/finding", admin, map[string]any{"room": "Hall", "site_note": "x"}, &out); st != 502 || out.Error.Code != "AI_FAILED" {
		t.Fatalf("bad content: %d %v", st, out)
	}
	// The 402 was refunded (DeepSeek billed nothing); the unreadable answer counts.
	var used int
	_ = h.db.QueryRow(t.Context(), `SELECT sum(requests - failures) FROM ai_usage`).Scan(&used)
	if used != 1 {
		t.Fatalf("counted %d requests, want 1", used)
	}
}

// Parallel requests must not get past the daily limit (the check and the reservation are one step).
func TestAILimitHoldsUnderParallelRequests(t *testing.T) {
	h, f := setupAI(t)
	h.user("ADM", "ADMIN")
	admin := h.login("ADM")
	h.do("PUT", "/v1/admin/ai/key", admin, map[string]string{"key": goodKey}, nil)
	h.do("PATCH", "/v1/admin/settings", admin, map[string]int{"ai_daily_limit": 3}, nil)
	f.content, f.delay = `{"title":"t"}`, 300*time.Millisecond

	var ok, limited atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			switch h.do("POST", "/v1/ai/finding", admin, map[string]any{"room": "Hall", "site_note": "x"}, nil) {
			case 200:
				ok.Add(1)
			case 429:
				limited.Add(1)
			}
		}()
	}
	wg.Wait()
	if ok.Load() != 3 || limited.Load() != 7 || f.calls != 3 {
		t.Fatalf("ok=%d limited=%d deepseek calls=%d, want 3/7/3", ok.Load(), limited.Load(), f.calls)
	}
}

func TestAIReviewMapsRefsToFindings(t *testing.T) {
	h, f := setupAI(t)
	h.user("ADM", "ADMIN")
	admin := h.login("ADM")
	h.do("PUT", "/v1/admin/ai/key", admin, map[string]string{"key": goodKey}, nil)
	f.content = `{"issues":[{"ref":"f2","kind":"duplicate","message":"Same leak as f1."},{"ref":"F9","kind":"UNCLEAR","message":"x"},{"ref":"F1","kind":"STYLE","message":"x"}]}`
	in := map[string]any{"rooms": []any{
		map[string]any{"name": "Kitchen", "findings": []any{map[string]any{"id": "a1", "title": "Leak under sink"}}},
		map[string]any{"name": "Utility", "findings": []any{map[string]any{"id": "b2", "title": "Leak under sink"}}},
	}}
	var out struct {
		Issues []map[string]string
	}
	if st := h.do("POST", "/v1/ai/review", admin, in, &out); st != 200 {
		t.Fatalf("review: %d", st)
	}
	if len(out.Issues) != 1 || out.Issues[0]["finding_id"] != "b2" || out.Issues[0]["kind"] != "DUPLICATE" ||
		out.Issues[0]["message"] != `Same leak as "Leak under sink" (Kitchen).` {
		t.Fatalf("issues: %v", out.Issues)
	}
	if f.last["reasoning_effort"] != "low" {
		t.Fatalf("review should think: %v", f.last)
	}

	f.content = `{"summary":"The property is in fair condition."}`
	var sum map[string]string
	if st := h.do("POST", "/v1/ai/summary", admin, in, &sum); st != 200 || sum["summary"] != "The property is in fair condition." {
		t.Fatalf("summary: %d %v", st, sum)
	}
}

// An app from before the summary field (0.5.1) must not wipe a summary written on a newer phone.
func TestInspectionSummaryKeptWhenPushOmitsIt(t *testing.T) {
	h := setup(t)
	h.user("ADM", "ADMIN")
	tok := h.login("ADM")
	id := newUUID()
	withSummary := inspection(id, 0, "m1:1", "Priya")
	withSummary["data"].(map[string]any)["summary"] = "Overall fair."
	if r := h.push(tok, withSummary); r[0].Status != "OK" {
		t.Fatalf("push: %+v", r[0])
	}
	if r := h.push(tok, inspection(id, 1, "m2:1", "Priya K")); r[0].Status != "OK" { // old app: no summary key
		t.Fatalf("old push: %+v", r[0])
	}
	var summary *string
	var client string
	_ = h.db.QueryRow(t.Context(), `SELECT summary, client_name FROM inspections WHERE id = $1`, id).Scan(&summary, &client)
	if summary == nil || *summary != "Overall fair." || client != "Priya K" {
		t.Fatalf("after old push: %v %q", summary, client)
	}
	cleared := inspection(id, 2, "m3:1", "Priya K")
	cleared["data"].(map[string]any)["summary"] = nil // new app clearing it on purpose
	h.push(tok, cleared)
	_ = h.db.QueryRow(t.Context(), `SELECT summary FROM inspections WHERE id = $1`, id).Scan(&summary)
	if summary != nil {
		t.Fatalf("explicit null not applied: %v", *summary)
	}
}
