package api

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Developer lock (migration 004). While locked, every request except /healthz is answered with
// 423 {"error": {"code": "APP_LOCKED", "message": ...}}, before authentication, so no client
// version can sign in, sync, upload or download. Phones show a full-screen notice; nothing is
// deleted on the server or on phones. Changed only by `griha-api lock|unlock` on the server.

// LockedStatus and LockedCode are the exact signal phones look for. Both must match: a 423 from
// anything else (a proxy, a WebDAV-style error) never locks a phone.
const (
	LockedStatus = http.StatusLocked // 423
	LockedCode   = "APP_LOCKED"

	DefaultLockMessage = "This app has been locked. Please contact your developer."
)

type lockState struct {
	locked  bool
	message string
	at      time.Time // when it was read from the database
}

type lockCache struct {
	mu    sync.Mutex
	state *lockState
}

// appLock returns the lock state, read from the database at most every s.lockTTL. If the database
// can't be read, the last known state stands (a lock set before an outage stays set); if there is
// none, requests go on as unlocked and fail on their own if the database is really down.
func (s *Server) appLock(ctx context.Context) lockState {
	s.lock.mu.Lock()
	defer s.lock.mu.Unlock()
	if st := s.lock.state; st != nil && s.now().Sub(st.at) < s.lockTTL {
		return *st
	}
	var st lockState
	err := s.db.QueryRow(ctx, `SELECT locked, message FROM app_lock WHERE id`).Scan(&st.locked, &st.message)
	if err != nil {
		s.log.Error("app lock read", "err", err)
		if s.lock.state != nil {
			return *s.lock.state
		}
		return lockState{}
	}
	st.at = s.now()
	s.lock.state = &st
	return st
}

func (s *Server) lockGate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// The web dashboard's static files stay reachable so it can show the lock message it gets from the API.
		if r.URL.Path != "/healthz" && r.URL.Path != "/" && !strings.HasPrefix(r.URL.Path, "/web/") {
			if st := s.appLock(r.Context()); st.locked {
				msg := st.message
				if msg == "" {
					msg = DefaultLockMessage
				}
				writeError(w, LockedStatus, LockedCode, msg)
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

// appStatus is checked by phones on every app start, and is what unlocks a locked phone: only a
// 200 with "locked": false does. (When locked, lockGate answers 423 before reaching here.)
func (s *Server) appStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"locked": false})
}

// SetAppLock locks or unlocks every phone and records the change. Used by the CLI only.
func SetAppLock(ctx context.Context, db *pgxpool.Pool, locked bool, message string) error {
	message = strings.TrimSpace(message)
	if !locked {
		message = ""
	} else if message == "" {
		message = DefaultLockMessage
	}
	if len(message) > 500 {
		return errors.New("message is longer than 500 characters")
	}
	return pgx.BeginFunc(ctx, db, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `UPDATE app_lock SET locked = $1, message = $2, changed_at = now() WHERE id`, locked, message)
		if err != nil {
			return err
		}
		if tag.RowsAffected() != 1 {
			return errors.New("app_lock row missing")
		}
		_, err = tx.Exec(ctx, `INSERT INTO app_lock_history (locked, message) VALUES ($1, $2)`, locked, message)
		return err
	})
}

// AppLockStatus reports the stored state, for `griha-api lock-status`.
func AppLockStatus(ctx context.Context, db *pgxpool.Pool) (locked bool, message string, changedAt time.Time, err error) {
	err = db.QueryRow(ctx, `SELECT locked, message, changed_at FROM app_lock WHERE id`).Scan(&locked, &message, &changedAt)
	return
}
