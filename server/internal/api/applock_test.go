package api

import (
	"context"
	"os"
	"testing"
)

type errBody struct {
	Error struct{ Code, Message string } `json:"error"`
}

func TestAppLock(t *testing.T) {
	h := setup(t)
	ctx := context.Background()
	h.user("deva", "ADMIN")
	token := h.login("deva")

	// Unlocked: status says so explicitly, everything works.
	var st struct {
		Locked *bool `json:"locked"`
	}
	if code := h.do("GET", "/v1/app/status", "", nil, &st); code != 200 || st.Locked == nil || *st.Locked {
		t.Fatalf("status unlocked: %d %v", code, st.Locked)
	}
	if code := h.do("GET", "/v1/me", token, nil, nil); code != 200 {
		t.Fatalf("me unlocked: %d", code)
	}

	if err := SetAppLock(ctx, h.db, true, "  Pay up  "); err != nil {
		t.Fatal(err)
	}
	// Locked: every route answers 423 APP_LOCKED with the message, signed in or not, before auth.
	for _, c := range []struct{ method, path, token string }{
		{"GET", "/v1/app/status", ""},
		{"GET", "/v1/me", token},
		{"POST", "/v1/auth/login", ""},
		{"GET", "/v1/sync/changes", token},
		{"GET", "/v1/sync/changes", "garbage"},
		{"GET", "/v1/admin/users", token},
		{"GET", "/v1/export-files/nope", ""},
		{"GET", "/v1/does-not-exist", ""},
	} {
		var e errBody
		if code := h.do(c.method, c.path, c.token, nil, &e); code != LockedStatus || e.Error.Code != LockedCode || e.Error.Message != "Pay up" {
			t.Fatalf("%s %s locked: %d %+v", c.method, c.path, code, e)
		}
	}
	// Docker's health check must keep passing, or the container would be restarted in a loop.
	if code := h.do("GET", "/healthz", "", nil, nil); code != 200 {
		t.Fatalf("healthz while locked: %d", code)
	}

	// Locking never deletes: sessions survive, so phones resume without signing in again.
	if err := SetAppLock(ctx, h.db, false, "ignored"); err != nil {
		t.Fatal(err)
	}
	if code := h.do("GET", "/v1/me", token, nil, nil); code != 200 {
		t.Fatalf("me after unlock: %d", code)
	}
	st.Locked = nil
	if code := h.do("GET", "/v1/app/status", "", nil, &st); code != 200 || st.Locked == nil || *st.Locked {
		t.Fatalf("status after unlock: %d %v", code, st.Locked)
	}

	// Empty message → the default; both changes are in the private history, not the admins' audit log.
	if err := SetAppLock(ctx, h.db, true, ""); err != nil {
		t.Fatal(err)
	}
	var e errBody
	if h.do("GET", "/v1/app/status", "", nil, &e); e.Error.Message != DefaultLockMessage {
		t.Fatalf("default message: %+v", e)
	}
	var history, audit int
	_ = h.db.QueryRow(ctx, `SELECT count(*) FROM app_lock_history`).Scan(&history)
	_ = h.db.QueryRow(ctx, `SELECT count(*) FROM audit_events WHERE type ILIKE '%lock%'`).Scan(&audit)
	if history != 3 || audit != 0 {
		t.Fatalf("history %d audit %d", history, audit)
	}
	locked, msg, _, err := AppLockStatus(ctx, h.db)
	if err != nil || !locked || msg != DefaultLockMessage {
		t.Fatalf("AppLockStatus: %v %q %v", locked, msg, err)
	}
	if err := SetAppLock(ctx, h.db, true, string(make([]byte, 501))); err == nil {
		t.Fatal("over-long message accepted")
	}
}

// A database that can't be read never locks anyone who wasn't locked before.
func TestAppLockFailsOpenWithoutKnownState(t *testing.T) {
	if os.Getenv("TEST_DATABASE_URL") == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	h := setup(t)
	if _, err := h.db.Exec(context.Background(), `ALTER TABLE app_lock RENAME TO app_lock_gone`); err != nil {
		t.Fatal(err)
	}
	var st struct{ Locked *bool }
	if code := h.do("GET", "/v1/app/status", "", nil, &st); code != 200 || st.Locked == nil || *st.Locked {
		t.Fatalf("unreadable lock: %d %v", code, st.Locked)
	}
}
