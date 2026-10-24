package api

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"
	"unicode"

	"github.com/jackc/pgx/v5"
)

// AI writing help. Phones never see the DeepSeek key: they send a finding's text here, the server
// adds the key and the instructions (prompts live here, so they can be tuned without an app
// release), and returns suggestions. The inspector accepts or discards each one on the phone;
// nothing here writes inspection data. Only finding text, room names and the property type are
// sent to DeepSeek, never client names, phone numbers or addresses.

const (
	aiDefaultModel = "deepseek-flash"
	aiFieldMax     = 4000 // characters per text field accepted from phones
	aiMaxFindings  = 300  // per summary / review request
)

// Stored severity values (Android enum names) and the labels inspectors and the report use.
var aiSeverities = []struct{ value, label, meaning string }{
	{"INFO", "Observation", "no defect; informational or routine maintenance note"},
	{"MINOR", "Low", "cosmetic or minor wear; fix at the owner's convenience"},
	{"MODERATE", "Moderate", "functional defect; repair within weeks to months before it worsens"},
	{"MAJOR", "High", "significant defect affecting function, durability or safety; repair soon by a professional"},
	{"URGENT", "Urgent", "immediate safety hazard or active damage; act now"},
}

func aiSeverityLabel(value string) string {
	for _, s := range aiSeverities {
		if s.value == value {
			return s.label
		}
	}
	return ""
}

// ---- The key (admin only, write-only) -------------------------------------------------------

const aiKeyAAD = "home-inspection-ai-key-v1"

func (s *Server) sealAIKey(key string) (nonce, ciphertext []byte, err error) {
	gcm, err := s.aiCipher()
	if err != nil {
		return nil, nil, err
	}
	nonce = make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, nil, err
	}
	return nonce, gcm.Seal(nil, nonce, []byte(key), []byte(aiKeyAAD)), nil
}

func (s *Server) aiCipher() (cipher.AEAD, error) {
	if len(s.cfg.AISecret) != 32 {
		return nil, errAIUnavailable
	}
	block, err := aes.NewCipher(s.cfg.AISecret)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

var (
	errAIUnavailable = errors.New("HOME_INSPECTION_SECRET_KEY is not set on the server")
	errAINoKey       = errors.New("no AI key")
)

// aiKey returns the stored DeepSeek key, decrypted.
func (s *Server) aiKey(ctx context.Context) (string, error) {
	gcm, err := s.aiCipher()
	if err != nil {
		return "", err
	}
	var nonce, ciphertext []byte
	err = s.db.QueryRow(ctx, `SELECT nonce, ciphertext FROM ai_key WHERE id`).Scan(&nonce, &ciphertext)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", errAINoKey
	}
	if err != nil {
		return "", err
	}
	plain, err := gcm.Open(nil, nonce, ciphertext, []byte(aiKeyAAD))
	if err != nil {
		return "", fmt.Errorf("AI key cannot be decrypted (HOME_INSPECTION_SECRET_KEY changed?): %w", err)
	}
	return string(plain), nil
}

func (s *Server) aiEnabled(ctx context.Context) bool {
	if len(s.cfg.AISecret) != 32 {
		return false
	}
	var set bool
	_ = s.db.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM ai_key)`).Scan(&set)
	return set && s.settingInt(ctx, "ai_daily_limit") > 0
}

func (s *Server) aiModel() string {
	if s.cfg.AIModel != "" {
		return s.cfg.AIModel
	}
	return aiDefaultModel
}

func (s *Server) adminAI(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	body := map[string]any{
		"available":      len(s.cfg.AISecret) == 32,
		"key_set":        false,
		"model":          s.aiModel(),
		"daily_limit":    s.settingInt(ctx, "ai_daily_limit"),
		"month_requests": 0,
		"month_tokens":   0,
		"users":          []any{},
	}
	var last4, setBy string
	var setAt time.Time
	err := s.db.QueryRow(ctx, `SELECT k.last4, k.set_at, COALESCE(u.display_name, '') FROM ai_key k LEFT JOIN users u ON u.id = k.set_by`).
		Scan(&last4, &setAt, &setBy)
	if err == nil {
		body["key_set"], body["key_last4"], body["key_set_at"], body["key_set_by"] = true, last4, setAt.UnixMilli(), setBy
	} else if !errors.Is(err, pgx.ErrNoRows) {
		s.internal(w, "admin ai", err)
		return
	}
	rows, err := s.db.Query(ctx, `
		SELECT COALESCE(u.display_name, u.employee_code), sum(a.requests)::bigint, sum(a.tokens_in + a.tokens_out)::bigint
		FROM ai_usage a JOIN users u ON u.id = a.user_id
		WHERE a.day >= date_trunc('month', now() AT TIME ZONE 'Asia/Kolkata')::date
		GROUP BY 1 ORDER BY 2 DESC`)
	if err != nil {
		s.internal(w, "admin ai usage", err)
		return
	}
	defer rows.Close()
	var users []any
	var total, tokens int64
	for rows.Next() {
		var name string
		var n, t int64
		if err := rows.Scan(&name, &n, &t); err != nil {
			s.internal(w, "admin ai usage", err)
			return
		}
		users = append(users, map[string]any{"name": name, "requests": n})
		total += n
		tokens += t
	}
	if users != nil {
		body["users"] = users
	}
	body["month_requests"], body["month_tokens"] = total, tokens
	writeJSON(w, http.StatusOK, body)
}

func (s *Server) setAIKey(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Key string `json:"key"`
	}
	if !readJSON(w, r, 4<<10, &req) {
		return
	}
	key := strings.TrimSpace(req.Key)
	if len(key) < 16 || len(key) > 200 || strings.ContainsFunc(key, unicode.IsSpace) {
		writeError(w, http.StatusBadRequest, "INVALID", "that doesn't look like a DeepSeek API key")
		return
	}
	if len(s.cfg.AISecret) != 32 {
		writeError(w, http.StatusServiceUnavailable, "AI_UNAVAILABLE", "the server has no HOME_INSPECTION_SECRET_KEY, so it cannot store the key safely")
		return
	}
	// Check the key with DeepSeek before storing it, so a typo is caught now rather than on site.
	if status, err := s.checkDeepSeekKey(r.Context(), key); err != nil {
		s.log.Error("deepseek key check", "err", err)
		writeError(w, http.StatusBadGateway, "AI_UNREACHABLE", "could not reach DeepSeek to check the key; try again")
		return
	} else if status == http.StatusUnauthorized || status == http.StatusForbidden {
		writeError(w, http.StatusBadRequest, "AI_KEY_REJECTED", "DeepSeek rejected this key; copy it again from the DeepSeek console")
		return
	} else if status != http.StatusOK {
		writeError(w, http.StatusBadGateway, "AI_UNREACHABLE", fmt.Sprintf("DeepSeek answered %d while checking the key; try again", status))
		return
	}
	nonce, ciphertext, err := s.sealAIKey(key)
	if err != nil {
		s.internal(w, "seal ai key", err)
		return
	}
	admin := userFrom(r)
	last4 := key[len(key)-4:]
	_, err = s.db.Exec(r.Context(), `INSERT INTO ai_key (id, nonce, ciphertext, last4, set_by) VALUES (true, $1, $2, $3, $4)
		ON CONFLICT (id) DO UPDATE SET nonce = EXCLUDED.nonce, ciphertext = EXCLUDED.ciphertext, last4 = EXCLUDED.last4,
		set_by = EXCLUDED.set_by, set_at = now()`, nonce, ciphertext, last4, admin.ID)
	if err != nil {
		s.internal(w, "store ai key", err)
		return
	}
	s.auditEvent(r.Context(), "AI_KEY_SET", &admin.ID, admin.deviceID, "", "DeepSeek key ending "+last4)
	s.adminAI(w, r)
}

func (s *Server) removeAIKey(w http.ResponseWriter, r *http.Request) {
	tag, err := s.db.Exec(r.Context(), `DELETE FROM ai_key`)
	if err != nil {
		s.internal(w, "remove ai key", err)
		return
	}
	if tag.RowsAffected() > 0 {
		admin := userFrom(r)
		s.auditEvent(r.Context(), "AI_KEY_REMOVED", &admin.ID, admin.deviceID, "", "")
	}
	s.adminAI(w, r)
}

func (s *Server) checkDeepSeekKey(ctx context.Context, key string) (int, error) {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, "GET", s.deepSeekURL()+"/models", nil)
	req.Header.Set("Authorization", "Bearer "+key)
	resp, err := s.ai.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
	return resp.StatusCode, nil
}

func (s *Server) deepSeekURL() string {
	if s.cfg.DeepSeekURL != "" {
		return strings.TrimRight(s.cfg.DeepSeekURL, "/")
	}
	return "https://api.deepseek.com"
}

// ---- Calling DeepSeek -----------------------------------------------------------------------

type aiCall struct {
	kind      string // FINDING | SUMMARY | REVIEW (usage accounting)
	system    string
	user      string
	maxTokens int
	think     bool          // low-effort reasoning; worth it only for the consistency check
	timeout   time.Duration // the phone waits up to 3 minutes for the review, 2 for the rest
}

// aiError is a failure the phone shows as-is.
type aiError struct {
	status        int
	code, message string
}

func (e *aiError) Error() string { return e.message }

// runAI checks the daily limit, calls DeepSeek in JSON mode and decodes the answer into out.
func (s *Server) runAI(ctx context.Context, u *authUser, call aiCall, out any) error {
	key, err := s.aiKey(ctx)
	if errors.Is(err, errAINoKey) || errors.Is(err, errAIUnavailable) {
		return &aiError{http.StatusServiceUnavailable, "AI_OFF", "AI suggestions are not set up. Ask the admin to add the DeepSeek key."}
	}
	if err != nil {
		return err
	}
	limit := s.settingInt(ctx, "ai_daily_limit")
	if limit <= 0 {
		return &aiError{http.StatusServiceUnavailable, "AI_OFF", "AI suggestions are turned off by the admin."}
	}
	// Reserve the request before calling DeepSeek, under a per-user lock: parallel requests (a
	// script with a stolen session) can never get past the limit. Refunds are counted in failures.
	full := false
	err = pgx.BeginFunc(ctx, s.db, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(7002, hashtext($1))`, u.ID); err != nil {
			return err
		}
		var used int
		if err := tx.QueryRow(ctx, `SELECT COALESCE(sum(requests - failures), 0) FROM ai_usage
			WHERE user_id = $1 AND day = (now() AT TIME ZONE 'Asia/Kolkata')::date`, u.ID).Scan(&used); err != nil {
			return err
		}
		if full = used >= limit; full {
			return nil
		}
		_, err := tx.Exec(ctx, `INSERT INTO ai_usage (user_id, day, kind, requests) VALUES ($1, (now() AT TIME ZONE 'Asia/Kolkata')::date, $2, 1)
			ON CONFLICT (user_id, day, kind) DO UPDATE SET requests = ai_usage.requests + 1`, u.ID, call.kind)
		return err
	})
	if err != nil {
		return err
	}
	if full {
		return &aiError{http.StatusTooManyRequests, "AI_LIMIT", fmt.Sprintf("Today's limit of %d AI requests is used up. It resets at midnight.", limit)}
	}

	reqBody := map[string]any{
		"model": s.aiModel(),
		"messages": []map[string]string{
			{"role": "system", "content": call.system},
			{"role": "user", "content": call.user},
		},
		"response_format": map[string]string{"type": "json_object"},
		"max_tokens":      call.maxTokens,
		"user_id":         u.ID,
	}
	if call.think {
		reqBody["reasoning_effort"] = "low"
	} else {
		reqBody["thinking"] = map[string]string{"type": "disabled"}
		reqBody["temperature"] = 0.3
	}
	raw, _ := json.Marshal(reqBody)

	var result struct {
		Choices []struct {
			FinishReason string `json:"finish_reason"`
			Message      struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
		Usage struct {
			PromptTokens     int64 `json:"prompt_tokens"`
			CompletionTokens int64 `json:"completion_tokens"`
		} `json:"usage"`
	}
	// refund: DeepSeek produced no billed answer (unreachable, rejected, busy), so the request
	// doesn't count against the inspector's daily limit.
	refund := false
	callErr := func() error {
		ctx, cancel := context.WithTimeout(ctx, call.timeout)
		defer cancel()
		req, _ := http.NewRequestWithContext(ctx, "POST", s.deepSeekURL()+"/chat/completions", bytes.NewReader(raw))
		req.Header.Set("Authorization", "Bearer "+key)
		req.Header.Set("Content-Type", "application/json")
		resp, err := s.ai.Do(req)
		if err != nil {
			if errors.Is(err, context.DeadlineExceeded) {
				s.log.Warn("deepseek timeout", "kind", call.kind, "after", call.timeout)
				return &aiError{http.StatusGatewayTimeout, "AI_TIMEOUT", "The AI took too long to answer. Try again."}
			}
			if errors.Is(err, context.Canceled) { // the phone went away; DeepSeek may have billed
				return err
			}
			s.log.Error("deepseek unreachable", "kind", call.kind, "err", err)
			refund = true
			return &aiError{http.StatusBadGateway, "AI_UNREACHABLE", "Could not reach the AI service. Try again in a minute."}
		}
		defer resp.Body.Close()
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
		if resp.StatusCode != http.StatusOK {
			refund = true
		}
		switch {
		case resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden:
			s.log.Error("deepseek key rejected", "status", resp.StatusCode)
			return &aiError{http.StatusBadGateway, "AI_KEY_INVALID", "DeepSeek rejected the AI key. Ask the admin to replace it."}
		case resp.StatusCode == http.StatusPaymentRequired:
			return &aiError{http.StatusBadGateway, "AI_NO_BALANCE", "The DeepSeek account is out of credit. Ask the admin to top it up."}
		case resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode >= 500:
			return &aiError{http.StatusServiceUnavailable, "AI_BUSY", "The AI service is busy. Try again in a minute."}
		case resp.StatusCode != http.StatusOK:
			s.log.Error("deepseek", "status", resp.StatusCode, "body", string(body[:min(len(body), 500)]))
			return &aiError{http.StatusBadGateway, "AI_FAILED", "The AI service could not handle this request."}
		}
		if err := json.Unmarshal(body, &result); err != nil || len(result.Choices) == 0 {
			return &aiError{http.StatusBadGateway, "AI_FAILED", "The AI service sent an unreadable answer. Try again."}
		}
		content := strings.TrimSpace(result.Choices[0].Message.Content)
		if content == "" || json.Unmarshal([]byte(content), out) != nil {
			s.log.Warn("deepseek content", "finish", result.Choices[0].FinishReason, "len", len(content))
			return &aiError{http.StatusBadGateway, "AI_FAILED", "The AI answer was incomplete. Try again."}
		}
		return nil
	}()

	failed := 0
	if refund {
		failed = 1
	}
	// Recorded even if the phone disconnected meanwhile (the request context is cancelled then).
	_, err = s.db.Exec(context.WithoutCancel(ctx), `UPDATE ai_usage SET failures = failures + $3, tokens_in = tokens_in + $4,
		tokens_out = tokens_out + $5 WHERE user_id = $1 AND day = (now() AT TIME ZONE 'Asia/Kolkata')::date AND kind = $2`,
		u.ID, call.kind, failed, result.Usage.PromptTokens, result.Usage.CompletionTokens)
	if err != nil {
		s.log.Error("ai usage", "err", err)
	}
	return callErr
}

func (s *Server) writeAIError(w http.ResponseWriter, where string, err error) {
	var ae *aiError
	if errors.As(err, &ae) {
		writeError(w, ae.status, ae.code, ae.message)
		return
	}
	s.internal(w, where, err)
}

// ---- Shared instructions ------------------------------------------------------------------

// The report's PDF prints Latin-1 only, and clients read it: plain English, plain punctuation.
const aiHouseStyle = `You write for Home Inspection, a residential property inspection company in India (Andhra Pradesh and Telangana). Reports go to home buyers and owners who are not engineers.

Rules for everything you write:
- Clear, professional, client-friendly English. Short sentences. No jargon without a plain explanation.
- The inspector's notes may be in English, Telugu, Hindi or a mix, possibly in Latin letters, with typos and shorthand. Understand them, but always answer in English.
- Use plain ASCII characters only: straight quotes, a normal hyphen "-" instead of dashes, "Rs." instead of the rupee sign. No emoji, no markdown, no bullet symbols.
- Personal details were removed before you see the text: "[phone]", "[email]", "the client", "the property" stand in for them. Never try to fill them in, and don't repeat the brackets in client-facing text.
- Never invent facts. Do not add measurements, locations, quantities, ages, causes or test results that the inspector did not give. Where a cause is not certain, say "likely" or "possible", or recommend investigation.
- No cost estimates, no brand names, no legal advice.
- Answer with a single JSON object only, exactly in the format requested.`

func aiSeverityGuide() string {
	var b strings.Builder
	b.WriteString("Severity values (use the value, not the label):\n")
	for _, s := range aiSeverities {
		fmt.Fprintf(&b, "- %s (%q): %s\n", s.value, s.label, s.meaning)
	}
	return b.String()
}

// Phone numbers and email addresses typed into notes never leave for DeepSeek. The phone already
// replaced this inspection's client name, phone and address; this catches anyone else's.
var (
	aiEmail = regexp.MustCompile(`[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}`)
	// 10+ digits, optionally +91 and spaces/hyphens/dots between groups (mobiles, landlines with STD code).
	aiPhone = regexp.MustCompile(`\+?\d(?:[\s.-]?\d){9,}`)
)

func scrubPII(s string) string {
	s = aiEmail.ReplaceAllString(s, "[email]")
	return aiPhone.ReplaceAllString(s, "[phone]")
}

// clip trims, scrubs personal contact details and caps the length of text bound for (or from) DeepSeek.
func clip(s string, n int) string {
	s = scrubPII(strings.TrimSpace(s))
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}

// asciiPunct maps the punctuation models like to use to what the report's font prints.
var asciiPunct = strings.NewReplacer(
	"\u2018", "'", "\u2019", "'", "\u201C", `"`, "\u201D", `"`, "\u2013", "-", "\u2014", "-",
	"\u2026", "...", "\u20B9", "Rs.", "\u00A0", " ", "\u2022", "-",
)

func cleanAI(s string, n int) string { return clip(asciiPunct.Replace(s), n) }

// ---- POST /v1/ai/finding ------------------------------------------------------------------

type aiFindingInput struct {
	Room           string   `json:"room"`
	PropertyType   string   `json:"property_type"`
	Category       string   `json:"category"`
	Severity       string   `json:"severity"`
	Title          string   `json:"title"`
	SiteNote       string   `json:"site_note"`
	Observation    string   `json:"observation"`
	Implication    string   `json:"implication"`
	Recommendation string   `json:"recommendation"`
	PhotoCaptions  []string `json:"photo_captions"`
	Categories     []string `json:"categories"` // the phone's category list, so the server never drifts from it
}

func (s *Server) aiFinding(w http.ResponseWriter, r *http.Request) {
	var in aiFindingInput
	if !readJSON(w, r, 64<<10, &in) {
		return
	}
	if strings.TrimSpace(in.SiteNote) == "" && strings.TrimSpace(in.Observation) == "" && strings.TrimSpace(in.Title) == "" {
		writeError(w, http.StatusBadRequest, "INVALID", "write a site note first")
		return
	}
	var categories []string
	for _, c := range in.Categories {
		if c = clip(c, 60); c != "" && len(categories) < 30 {
			categories = append(categories, c)
		}
	}

	var b strings.Builder
	fmt.Fprintf(&b, "Room / area: %s\n", clip(in.Room, 100))
	if in.PropertyType != "" {
		fmt.Fprintf(&b, "Property type: %s\n", clip(in.PropertyType, 60))
	}
	if l := aiSeverityLabel(in.Severity); l != "" {
		fmt.Fprintf(&b, "Severity chosen by the inspector: %s (%s)\n", in.Severity, l)
	}
	if in.Category != "" {
		fmt.Fprintf(&b, "Category chosen by the inspector: %s\n", clip(in.Category, 60))
	}
	field := func(label, v string) {
		if v = clip(v, aiFieldMax); v != "" {
			fmt.Fprintf(&b, "\n%s:\n%s\n", label, v)
		}
	}
	field("Current title", in.Title)
	field("Site note (the inspector's raw notes from the site)", in.SiteNote)
	field("Current detailed observation", in.Observation)
	field("Current implication", in.Implication)
	field("Current recommendation", in.Recommendation)
	var captions []string
	for _, c := range in.PhotoCaptions {
		if c = clip(c, 300); c != "" && len(captions) < 20 {
			captions = append(captions, c)
		}
	}
	if len(captions) > 0 {
		fmt.Fprintf(&b, "\nPhoto captions:\n- %s\n", strings.Join(captions, "\n- "))
	}

	system := aiHouseStyle + `

Task: turn the inspector's notes for ONE finding into report text. Keep every fact the inspector gave; drop nothing important. If a "current" field already has text, improve its wording and keep its facts.

Write:
- "title": what the issue is and where, at most 8 words, sentence case, no full stop. Example: "Hairline crack above bedroom window".
- "observation": what was seen, where and how extensive, objectively, 1 to 3 sentences.
- "implication": why it matters to the owner - the risk or consequence if left unaddressed, 1 to 2 sentences.
- "recommendation": the action to take, who should do it (for example "a licensed electrician", "a waterproofing specialist", "a structural engineer") and how soon, consistent with the severity, 1 to 2 sentences.
- "severity": your suggested severity value, and "severity_reason": one short sentence why.
` + aiSeverityGuide()
	if len(categories) > 0 {
		system += `- "category": the best match from exactly this list: ` + strings.Join(categories, " | ") + "\n"
	}
	system += `
JSON format: {"title": "...", "observation": "...", "implication": "...", "recommendation": "...", "severity": "MODERATE", "severity_reason": "...", "category": "..."}`

	var out struct {
		Title, Observation, Implication, Recommendation, Severity, Category string
		SeverityReason                                                      string `json:"severity_reason"`
	}
	if err := s.runAI(r.Context(), userFrom(r), aiCall{kind: "FINDING", system: system, user: b.String(), maxTokens: 1200, timeout: 60 * time.Second}, &out); err != nil {
		s.writeAIError(w, "ai finding", err)
		return
	}
	resp := map[string]any{
		"title":          cleanAI(out.Title, 120),
		"observation":    cleanAI(out.Observation, 2000),
		"implication":    cleanAI(out.Implication, 2000),
		"recommendation": cleanAI(out.Recommendation, 2000),
	}
	if aiSeverityLabel(out.Severity) != "" {
		resp["severity"], resp["severity_reason"] = out.Severity, cleanAI(out.SeverityReason, 300)
	}
	for _, c := range categories {
		if strings.EqualFold(c, strings.TrimSpace(out.Category)) {
			resp["category"] = c
		}
	}
	writeJSON(w, http.StatusOK, resp)
}

// ---- Whole-inspection input (summary and review) -------------------------------------------

type aiInspectionInput struct {
	PropertyType string `json:"property_type"`
	Rooms        []struct {
		Name     string `json:"name"`
		Findings []struct {
			ID             string `json:"id"`
			Title          string `json:"title"`
			Severity       string `json:"severity"`
			Category       string `json:"category"`
			SiteNote       string `json:"site_note"`
			Observation    string `json:"observation"`
			Implication    string `json:"implication"`
			Recommendation string `json:"recommendation"`
		} `json:"findings"`
	} `json:"rooms"`
}

type aiRef struct{ id, label string }

// describe renders the inspection as numbered findings (F1, F2, …) and returns, per ref, the
// finding's id and how to name it to the inspector.
func (in *aiInspectionInput) describe(withNotes bool) (string, map[string]aiRef, int) {
	var b strings.Builder
	refs := map[string]aiRef{}
	n := 0
	if in.PropertyType != "" {
		fmt.Fprintf(&b, "Property type: %s\n", clip(in.PropertyType, 60))
	}
	oneLine := func(v string, n int) string { return strings.Join(strings.Fields(clip(v, n)), " ") }
rooms:
	for _, room := range in.Rooms {
		fmt.Fprintf(&b, "\n## %s\n", oneLine(room.Name, 100))
		if len(room.Findings) == 0 {
			b.WriteString("(no findings)\n")
		}
		for _, f := range room.Findings {
			if n >= aiMaxFindings {
				b.WriteString("\n(more findings not shown)\n")
				break rooms
			}
			n++
			ref := fmt.Sprintf("F%d", n)
			title := strings.ReplaceAll(oneLine(f.Title, 60), `"`, "'")
			if title == "" {
				title = "untitled finding"
			}
			refs[ref] = aiRef{f.ID, fmt.Sprintf(`"%s" (%s)`, title, oneLine(room.Name, 40))}
			fmt.Fprintf(&b, "\n[%s] %s\n", ref, oneLine(f.Title, 200))
			if l := aiSeverityLabel(f.Severity); l != "" {
				fmt.Fprintf(&b, "Severity: %s (%s)\n", f.Severity, l)
			} else {
				b.WriteString("Severity: not set\n")
			}
			if f.Category != "" {
				fmt.Fprintf(&b, "Category: %s\n", oneLine(f.Category, 60))
			}
			line := func(label, v string) {
				if v = clip(v, 1500); v != "" {
					fmt.Fprintf(&b, "%s: %s\n", label, strings.ReplaceAll(v, "\n", " "))
				} else {
					fmt.Fprintf(&b, "%s: (empty)\n", label)
				}
			}
			if withNotes {
				line("Site note (not printed in the report)", f.SiteNote)
			}
			line("Observation", f.Observation)
			line("Implication", f.Implication)
			line("Recommendation", f.Recommendation)
		}
	}
	return b.String(), refs, n
}

// ---- POST /v1/ai/summary ------------------------------------------------------------------

func (s *Server) aiSummary(w http.ResponseWriter, r *http.Request) {
	var in aiInspectionInput
	if !readJSON(w, r, 1<<20, &in) {
		return
	}
	text, _, n := in.describe(false)
	if n == 0 {
		writeError(w, http.StatusBadRequest, "INVALID", "record some findings first")
		return
	}
	system := aiHouseStyle + `

Task: write the overview paragraph for the summary page of this inspection report, from the findings below.
- 80 to 160 words, one or two paragraphs, addressed to the client in the third person ("The property ...").
- Give the overall picture, then the most important concerns, most severe first, naming the area for each.
- Say what to prioritise. Mention minor items only as a group ("several minor finishing defects").
- Use only the findings given. If there are no significant findings, say so plainly.
- No greeting, no sign-off, no headings, no list of every finding (the report lists them after this).
` + aiSeverityGuide() + `
JSON format: {"summary": "..."}`
	var out struct{ Summary string }
	if err := s.runAI(r.Context(), userFrom(r), aiCall{kind: "SUMMARY", system: system, user: text, maxTokens: 900, timeout: 60 * time.Second}, &out); err != nil {
		s.writeAIError(w, "ai summary", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"summary": cleanAI(out.Summary, 3000)})
}

// ---- POST /v1/ai/review -------------------------------------------------------------------

var aiRefPattern = regexp.MustCompile(`(?i)\[?\bF\d+\b\]?`)

var aiIssueKinds = map[string]bool{"MISSING_DETAIL": true, "SEVERITY": true, "DUPLICATE": true, "UNCLEAR": true}

func (s *Server) aiReview(w http.ResponseWriter, r *http.Request) {
	var in aiInspectionInput
	if !readJSON(w, r, 1<<20, &in) {
		return
	}
	text, refs, n := in.describe(true)
	if n == 0 {
		writeError(w, http.StatusBadRequest, "INVALID", "record some findings first")
		return
	}
	system := aiHouseStyle + `

Task: check this inspection report before it is sent to the client, like a careful senior inspector. Report only real problems:
- MISSING_DETAIL: observation or recommendation is empty or too vague to act on, or the site note has an important fact the report text leaves out.
- SEVERITY: the severity does not match what the text describes (for example exposed wiring rated Low), or is not set. Say which severity fits and why.
- DUPLICATE: the same issue recorded twice. Name the other finding by its reference.
- UNCLEAR: confusing, contradictory or badly worded text a client would trip over (including spelling mistakes).
Do not report style preferences. Do not invent problems. At most 25 issues; most important first. If everything is fine, return an empty list.
Each "message" is one or two short sentences telling the inspector what to fix.
` + aiSeverityGuide() + `
JSON format: {"issues": [{"ref": "F3", "kind": "SEVERITY", "message": "..."}]}`
	var out struct {
		Issues []struct{ Ref, Kind, Message string }
	}
	if err := s.runAI(r.Context(), userFrom(r), aiCall{kind: "REVIEW", system: system, user: text, maxTokens: 6000, think: true, timeout: 150 * time.Second}, &out); err != nil {
		s.writeAIError(w, "ai review", err)
		return
	}
	issues := []map[string]string{}
	for _, i := range out.Issues {
		ref, ok := refs[strings.ToUpper(strings.Trim(strings.TrimSpace(i.Ref), "[]"))]
		kind := strings.ToUpper(strings.TrimSpace(i.Kind))
		// "Same issue as F2" means nothing on the phone: name the finding instead.
		msg := aiRefPattern.ReplaceAllStringFunc(cleanAI(i.Message, 500), func(m string) string {
			if other, ok := refs[strings.ToUpper(strings.Trim(m, "[]"))]; ok {
				return other.label
			}
			return m
		})
		if !ok || !aiIssueKinds[kind] || msg == "" || len(issues) >= 25 {
			continue
		}
		issues = append(issues, map[string]string{"finding_id": ref.id, "kind": kind, "message": msg})
	}
	writeJSON(w, http.StatusOK, map[string]any{"issues": issues})
}
