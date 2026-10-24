package api

import (
	"context"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
)

// ---- Password hashing (PRD §5.1: hashed server-side, never stored in plaintext) ------------------
//
// PBKDF2-HMAC-SHA256 from the Go standard library (no extra dependency). 600,000 iterations is the
// OWASP 2023+ recommendation; ~0.2 s per login on this 2-vCPU box, negligible at our login rate.

const pbkdf2Iterations = 600_000

func HashPassword(password string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key, err := pbkdf2.Key(sha256.New, password, salt, pbkdf2Iterations, 32)
	if err != nil {
		return "", err
	}
	enc := base64.RawStdEncoding
	return fmt.Sprintf("pbkdf2-sha256$%d$%s$%s", pbkdf2Iterations, enc.EncodeToString(salt), enc.EncodeToString(key)), nil
}

func verifyPassword(encoded, password string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 4 || parts[0] != "pbkdf2-sha256" {
		return false
	}
	iter, err := strconv.Atoi(parts[1])
	if err != nil || iter < 1 {
		return false
	}
	enc := base64.RawStdEncoding
	salt, err1 := enc.DecodeString(parts[2])
	want, err2 := enc.DecodeString(parts[3])
	if err1 != nil || err2 != nil {
		return false
	}
	got, err := pbkdf2.Key(sha256.New, password, salt, iter, len(want))
	return err == nil && subtle.ConstantTimeCompare(got, want) == 1
}

// Spent on unknown employee codes so a login's timing does not reveal which codes exist.
var dummyHash, _ = HashPassword("not-a-real-password")

// New passwords only; existing shorter ones keep working until changed.
const minPasswordLen = 10

func validPassword(p string) bool { return len([]rune(p)) >= minPasswordLen && len(p) <= 200 }

// ---- Login rate gate -----------------------------------------------------------------------
//
// Every login costs ~0.2 s of CPU (PBKDF2) on a box shared with other apps. Failure counters
// alone don't bound that: rotating made-up employee codes never trips them. So each IP gets
// loginPerMinute attempts at once, refilled at that rate, checked BEFORE any hashing.

const loginPerMinute = 10

type loginGate struct {
	burst     float64 // attempts available at once; refilled at the same number per minute
	mu        sync.Mutex
	buckets   map[string]*bucket
	lastPrune time.Time
}

type bucket struct {
	tokens float64
	at     time.Time
}

func newLoginGate(perMinute int) *loginGate {
	if perMinute <= 0 {
		perMinute = loginPerMinute
	}
	return &loginGate{burst: float64(perMinute), buckets: map[string]*bucket{}}
}

func (g *loginGate) allow(ip string, now time.Time) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if now.Sub(g.lastPrune) > 10*time.Minute { // a full bucket is the same as no entry
		g.lastPrune = now
		for k, b := range g.buckets {
			if now.Sub(b.at) > 2*time.Minute {
				delete(g.buckets, k)
			}
		}
	}
	b := g.buckets[ip]
	if b == nil {
		if len(g.buckets) >= maxTrackedKeys {
			return false // under a flood from very many IPs, fail closed rather than burn the CPU
		}
		b = &bucket{tokens: g.burst, at: now}
		g.buckets[ip] = b
	}
	b.tokens = min(g.burst, b.tokens+now.Sub(b.at).Minutes()*g.burst)
	b.at = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// ---- Login throttling ----------------------------------------------------------------------
//
// Two counters per attempt: employee code + client IP (5 free failures), and employee code alone
// (15 free failures, so rotating IPs doesn't help). Past the allowance each failure adds a minute.
// Entries expire 15 minutes after their last failure; a flood of new keys can never evict an
// active lockout, because only expired entries are dropped and new keys are refused when full.

const (
	perIPAllowance      = 5
	perAccountAllowance = 15
	failureMemory       = 15 * time.Minute
	maxTrackedKeys      = 50_000
)

type loginLimiter struct {
	mu        sync.Mutex
	failures  map[string]*failure
	lastPrune time.Time
}

type failure struct {
	count int
	until time.Time
	last  time.Time
}

func newLoginLimiter() *loginLimiter { return &loginLimiter{failures: map[string]*failure{}} }

func limiterKeys(code, ip string) (perIP, perAccount string) { return code + "|" + ip, code }

// blocked: [knownDevice] = the request comes from a phone already signed in as this user before.
// Strangers' failures then don't lock that phone out (the per-account counter exists to stop
// guessing from new places, not to let anyone who knows a code lock its owner out).
func (l *loginLimiter) blocked(code, ip string, knownDevice bool, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	a, b := limiterKeys(code, ip)
	keys := []string{a, b}
	if knownDevice {
		keys = keys[:1]
	}
	for _, k := range keys {
		if f := l.failures[k]; f != nil && now.Before(f.until) {
			return true
		}
	}
	return false
}

func (l *loginLimiter) fail(code, ip string, now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	a, b := limiterKeys(code, ip)
	l.bump(a, perIPAllowance, now)
	l.bump(b, perAccountAllowance, now)
}

func (l *loginLimiter) bump(key string, allowance int, now time.Time) {
	f := l.failures[key]
	if f == nil {
		if len(l.failures) >= maxTrackedKeys {
			// At most one full scan a minute, so a flood can't turn this into a CPU sink.
			if now.Sub(l.lastPrune) > time.Minute {
				l.lastPrune = now
				l.prune(now)
			}
			if len(l.failures) >= maxTrackedKeys {
				return // full of live entries: refuse new keys rather than forget lockouts
			}
		}
		f = &failure{}
		l.failures[key] = f
	}
	f.count++
	f.last = now
	if f.count >= allowance {
		f.until = now.Add(time.Duration(f.count-allowance+1) * time.Minute)
	}
}

func (l *loginLimiter) prune(now time.Time) {
	for k, f := range l.failures {
		if now.After(f.until) && now.Sub(f.last) > failureMemory {
			delete(l.failures, k)
		}
	}
}

// reset clears the per-IP counter after a correct password. The per-account counter is left to
// expire by itself, so a correct login from one place doesn't reset a guessing run elsewhere.
func (l *loginLimiter) reset(code, ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	a, _ := limiterKeys(code, ip)
	delete(l.failures, a)
}

// clientIP: Caddy is the only ingress (the API port is not published) and overwrites X-Real-IP
// with the real peer address (header_up X-Real-IP {remote_host}), so clients cannot spoof it.
func clientIP(r *http.Request) string {
	if ip := r.Header.Get("X-Real-IP"); ip != "" {
		return ip
	}
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	return host
}

// ---- Sessions ------------------------------------------------------------------------------

type authUser struct {
	ID           string `json:"id"`
	EmployeeCode string `json:"employee_code"`
	DisplayName  string `json:"display_name"`
	Role         string `json:"role"`
	MustChange   bool   `json:"must_change_password"`
	deviceID     *string
	tokenHash    []byte
	web          bool // signed in on the web dashboard (cookie session)
}

type ctxKey struct{}

// Sessions slide (30 days from last use) but never outlive this, so a stolen token dies eventually.
// Phones then sign in again online; offline sign-in on the phone is unaffected.
const sessionMaxAge = "90 days"

var mustChangeAllowed = map[string]bool{"/v1/auth/password": true, "/v1/auth/logout": true, "/v1/me": true}

func userFrom(r *http.Request) *authUser { return r.Context().Value(ctxKey{}).(*authUser) }

func tokenHash(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

// auth requires a live session of an active user on a non-revoked device. Checked on every
// request, so disabling an account or device takes effect at the device's next contact (PRD §10).
func (s *Server) auth(next http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Phones send a bearer token; the web dashboard a cookie (see web.go). Each kind of
		// session is only accepted the way it was issued.
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		web := false
		if !ok || token == "" {
			token, web = webToken(r)
			if token == "" {
				writeError(w, http.StatusUnauthorized, "UNAUTHENTICATED", "sign in required")
				return
			}
			if !webRequestAllowed(r) {
				writeError(w, http.StatusForbidden, "FORBIDDEN", "missing X-Griha-Web header")
				return
			}
			if !webRouteAllowed(r) {
				writeError(w, http.StatusForbidden, "NOT_ON_WEB", "this is only available in the app")
				return
			}
		}
		hash := tokenHash(token)
		u := &authUser{tokenHash: hash, web: web}
		var lastUsed time.Time
		err := s.db.QueryRow(r.Context(), `
			SELECT u.id::text, u.employee_code, u.display_name, u.role, u.must_change_password, s.device_id::text, s.last_used_at
			FROM sessions s JOIN users u ON u.id = s.user_id
			LEFT JOIN devices d ON d.id = s.device_id
			WHERE s.token_hash = $1 AND s.revoked_at IS NULL AND s.expires_at > now()
			  AND s.created_at > now() - $2::interval AND s.web = $3
			  AND u.status = 'ACTIVE' AND d.revoked_at IS NULL`, hash, sessionMaxAge, web,
		).Scan(&u.ID, &u.EmployeeCode, &u.DisplayName, &u.Role, &u.MustChange, &u.deviceID, &lastUsed)
		if errors.Is(err, pgx.ErrNoRows) {
			writeError(w, http.StatusUnauthorized, "SESSION_INVALID", "session expired or revoked; sign in again")
			return
		}
		if err != nil {
			s.internal(w, "auth", err)
			return
		}
		// A temporary password from the admin must be replaced before anything else (not only in the app).
		if u.MustChange && !mustChangeAllowed[r.URL.Path] {
			writeError(w, http.StatusForbidden, "PASSWORD_CHANGE_REQUIRED", "change your temporary password first")
			return
		}
		// Sliding expiry, written at most hourly to keep reads cheap.
		ttl, every := s.cfg.SessionTTL, time.Hour
		if web {
			ttl, every = webSessionTTL, 5*time.Minute
		}
		if s.now().Sub(lastUsed) > every {
			_, _ = s.db.Exec(r.Context(), `UPDATE sessions SET last_used_at = now(), expires_at = now() + $2::interval WHERE token_hash = $1`,
				hash, fmt.Sprintf("%d seconds", int(ttl.Seconds())))
			if u.deviceID != nil {
				_, _ = s.db.Exec(r.Context(), `UPDATE devices SET last_seen_at = now() WHERE id = $1`, *u.deviceID)
			}
		}
		next(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, u)))
	})
}

func (s *Server) admin(next http.HandlerFunc) http.Handler {
	return s.auth(func(w http.ResponseWriter, r *http.Request) {
		if userFrom(r).Role != "ADMIN" {
			writeError(w, http.StatusForbidden, "FORBIDDEN", "administrators only")
			return
		}
		next(w, r)
	})
}

type deviceInfo struct {
	ID         string `json:"id"`
	Label      string `json:"label"`
	Platform   string `json:"platform"`
	AppVersion string `json:"app_version"`
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var req struct {
		EmployeeCode string     `json:"employee_code"`
		Password     string     `json:"password"`
		Device       deviceInfo `json:"device"`
	}
	if !readJSON(w, r, 16<<10, &req) {
		return
	}
	if !isUUID(req.Device.ID) {
		writeError(w, http.StatusBadRequest, "INVALID", "employee_code, password and device.id (UUID) are required")
		return
	}
	u, ok := s.checkCredentials(w, r, req.EmployeeCode, req.Password, req.Device.ID)
	if !ok {
		return
	}

	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		s.internal(w, "login", err)
		return
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	expires := s.now().Add(s.cfg.SessionTTL)
	err := pgx.BeginFunc(r.Context(), s.db, func(tx pgx.Tx) error {
		var revoked *time.Time
		var owner string
		err := tx.QueryRow(r.Context(), `
			INSERT INTO devices (id, user_id, device_label, platform, app_version) VALUES ($1, $2, $3, $4, $5)
			ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id, device_label = EXCLUDED.device_label,
				platform = EXCLUDED.platform, app_version = EXCLUDED.app_version, last_seen_at = now()
			RETURNING revoked_at, user_id::text`,
			req.Device.ID, u.ID, trim(req.Device.Label, 100), trim(req.Device.Platform, 100), trim(req.Device.AppVersion, 50),
		).Scan(&revoked, &owner)
		if err != nil {
			return err
		}
		if revoked != nil {
			return errDeviceRevoked
		}
		_, err = tx.Exec(r.Context(), `INSERT INTO sessions (token_hash, user_id, device_id, expires_at) VALUES ($1, $2, $3, $4)`,
			tokenHash(token), u.ID, req.Device.ID, expires)
		return err
	})
	if errors.Is(err, errDeviceRevoked) {
		writeError(w, http.StatusForbidden, "DEVICE_REVOKED", "this device has been blocked; contact your administrator")
		return
	}
	if err != nil {
		s.internal(w, "login", err)
		return
	}
	s.auditEvent(r.Context(), "LOGIN", &u.ID, &req.Device.ID, "", "")
	writeJSON(w, http.StatusOK, map[string]any{
		"token": token, "expires_at": millis(expires), "user": u, "server_time": millis(s.now()),
	})
}

var errDeviceRevoked = errors.New("device revoked")

// checkCredentials runs the sign-in checks shared by phones and the web: rate gate, lockouts,
// password, account status. deviceID "" = the web (no device, never a "known phone").
// On failure it has written the response.
func (s *Server) checkCredentials(w http.ResponseWriter, r *http.Request, employeeCode, password, deviceID string) (*authUser, bool) {
	code := strings.ToUpper(strings.TrimSpace(employeeCode))
	if code == "" || password == "" {
		writeError(w, http.StatusBadRequest, "INVALID", "employee_code and password are required")
		return nil, false
	}
	ip := clientIP(r)
	if !s.loginGate.allow(ip, s.now()) {
		writeError(w, http.StatusTooManyRequests, "TOO_MANY_ATTEMPTS", "too many sign-in attempts from this network; wait a minute and try again")
		return nil, false
	}

	u := &authUser{}
	var hash, status string
	var knownDevice bool
	err := s.db.QueryRow(r.Context(),
		`SELECT u.id::text, u.employee_code, u.display_name, u.role, u.must_change_password, u.password_hash, u.status,
		        EXISTS (SELECT 1 FROM devices d WHERE d.id::text = $2 AND d.user_id = u.id AND d.revoked_at IS NULL)
		 FROM users u WHERE u.employee_code = $1`, code, deviceID,
	).Scan(&u.ID, &u.EmployeeCode, &u.DisplayName, &u.Role, &u.MustChange, &hash, &status, &knownDevice)
	if s.limiter.blocked(code, ip, knownDevice, s.now()) {
		writeError(w, http.StatusTooManyRequests, "TOO_MANY_ATTEMPTS", "too many failed attempts; wait a minute and try again")
		return nil, false
	}
	if errors.Is(err, pgx.ErrNoRows) {
		verifyPassword(dummyHash, password)
		s.limiter.fail(code, ip, s.now())
		writeError(w, http.StatusUnauthorized, "BAD_CREDENTIALS", "wrong employee code or password")
		return nil, false
	}
	if err != nil {
		s.internal(w, "login", err)
		return nil, false
	}
	if !verifyPassword(hash, password) {
		s.limiter.fail(code, ip, s.now())
		s.auditEvent(r.Context(), "LOGIN_FAILED", &u.ID, nil, "", "ip="+ip)
		writeError(w, http.StatusUnauthorized, "BAD_CREDENTIALS", "wrong employee code or password")
		return nil, false
	}
	if status != "ACTIVE" {
		writeError(w, http.StatusForbidden, "ACCOUNT_DISABLED", "this account is disabled; contact your administrator")
		return nil, false
	}
	s.limiter.reset(code, ip)
	return u, true
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	if _, err := s.db.Exec(r.Context(), `UPDATE sessions SET revoked_at = now() WHERE token_hash = $1`, u.tokenHash); err != nil {
		s.internal(w, "logout", err)
		return
	}
	if u.web {
		clearWebCookie(w)
	}
	w.WriteHeader(http.StatusNoContent)
}

// me doubles as the client's periodic "server check" that renews its 7-day offline window.
func (s *Server) me(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"user": userFrom(r), "server_time": millis(s.now())})
}

func (s *Server) changePassword(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	var req struct {
		Current string `json:"current_password"`
		New     string `json:"new_password"`
	}
	if !readJSON(w, r, 4<<10, &req) {
		return
	}
	if !validPassword(req.New) {
		writeError(w, http.StatusBadRequest, "WEAK_PASSWORD", fmt.Sprintf("use at least %d characters", minPasswordLen))
		return
	}
	var hash string
	if err := s.db.QueryRow(r.Context(), `SELECT password_hash FROM users WHERE id = $1`, u.ID).Scan(&hash); err != nil {
		s.internal(w, "password", err)
		return
	}
	if !verifyPassword(hash, req.Current) {
		writeError(w, http.StatusUnauthorized, "BAD_CREDENTIALS", "current password is wrong")
		return
	}
	newHash, err := HashPassword(req.New)
	if err != nil {
		s.internal(w, "password", err)
		return
	}
	err = pgx.BeginFunc(r.Context(), s.db, func(tx pgx.Tx) error {
		if _, err := tx.Exec(r.Context(), `UPDATE users SET password_hash = $2, must_change_password = false, updated_at = now() WHERE id = $1`, u.ID, newHash); err != nil {
			return err
		}
		// Sign out every other device; this one keeps working.
		_, err := tx.Exec(r.Context(), `UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND token_hash <> $2 AND revoked_at IS NULL`, u.ID, u.tokenHash)
		return err
	})
	if err != nil {
		s.internal(w, "password", err)
		return
	}
	s.auditEvent(r.Context(), "PASSWORD_CHANGED", &u.ID, u.deviceID, "", "")
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) internal(w http.ResponseWriter, where string, err error) {
	s.log.Error(where, "err", err)
	writeError(w, http.StatusInternalServerError, "INTERNAL", "server error; try again later")
}

func trim(s string, max int) string {
	s = strings.TrimSpace(s)
	if len(s) > max {
		return s[:max]
	}
	return s
}
