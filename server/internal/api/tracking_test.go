package api

import (
	"context"
	"strconv"
	"testing"
	"time"
)

func point(at int64, lat, lng float64, battery int) map[string]any {
	return map[string]any{"id": newUUID(), "client_at": at, "lat": lat, "lng": lng, "accuracy_m": 12.5, "battery": battery, "is_mock": false}
}

func trackEvent(kind string, at int64, extra map[string]any) map[string]any {
	e := map[string]any{"id": newUUID(), "kind": kind, "client_at": at}
	for k, v := range extra {
		e[k] = v
	}
	return e
}

type teamOut struct {
	Engineers []struct {
		UserID      string   `json:"user_id"`
		State       string   `json:"state"`
		Reason      *string  `json:"reason"`
		Note        *string  `json:"note"`
		Source      *string  `json:"source"`
		OnDuty      bool     `json:"on_duty"`
		Lat         *float64 `json:"lat"`
		Battery     *int     `json:"battery"`
		MockToday   bool     `json:"mock_today"`
		LiveUntil   *int64   `json:"live_until"`
		LastContact *int64   `json:"last_contact"`
	} `json:"engineers"`
}

func (h *harness) teamState(token, user string) (string, teamOut) {
	h.t.Helper()
	var out teamOut
	if st := h.do("GET", "/v1/admin/tracking/team", token, nil, &out); st != 200 {
		h.t.Fatalf("team: %d", st)
	}
	for _, e := range out.Engineers {
		if e.UserID == user {
			return e.State, out
		}
	}
	h.t.Fatalf("user %s not in team", user)
	return "", out
}

func TestTrackingBatchAndTeam(t *testing.T) {
	h := setup(t)
	h.user("deva", "ADMIN")
	h.user("insp", "INSPECTOR")
	h.user("other", "INSPECTOR")
	admin, insp, other := h.login("deva"), h.login("insp"), h.login("other")
	inspID := userID(h, "insp")
	now := time.Now().UnixMilli()

	if st, _ := h.teamState(admin, inspID); st != "OFF" {
		t.Fatalf("fresh engineer: %s", st)
	}
	if st := h.do("GET", "/v1/admin/tracking/team", insp, nil, nil); st != 403 {
		t.Fatalf("inspector saw the team: %d", st)
	}
	if st := h.do("POST", "/v1/tracking/batch", other, map[string]any{"user_id": inspID, "points": []any{point(now, 17.4, 78.4, 80)}}, nil); st != 403 {
		t.Fatalf("someone else's points accepted: %d", st)
	}

	start := trackEvent("START", now-60_000, map[string]any{"source": "MANUAL"})
	bad := point(now, 95, 78.4, 80) // latitude out of range: skipped, not fatal
	batch := map[string]any{"user_id": inspID, "points": []any{point(now-30_000, 17.40, 78.40, 80), point(now, 17.41, 78.41, 79), bad},
		"events": []any{start, trackEvent("CONSENT", now-70_000, map[string]any{"note": "v1"})}}
	var res struct {
		Skipped   int    `json:"skipped"`
		LiveUntil *int64 `json:"live_until"`
	}
	if st := h.do("POST", "/v1/tracking/batch", insp, batch, &res); st != 200 || res.Skipped != 1 || res.LiveUntil != nil {
		t.Fatalf("batch: %d %+v", st, res)
	}
	// A retried batch is a no-op (and audits nothing twice).
	if st := h.do("POST", "/v1/tracking/batch", insp, batch, nil); st != 200 {
		t.Fatalf("retry: %d", st)
	}
	var n int
	h.db.QueryRow(context.Background(), `SELECT count(*) FROM location_points WHERE user_id = $1`, inspID).Scan(&n)
	if n != 2 {
		t.Fatalf("points stored: %d", n)
	}
	h.db.QueryRow(context.Background(), `SELECT count(*) FROM audit_events WHERE type = 'TRACKING_CONSENT'`).Scan(&n)
	if n != 1 {
		t.Fatalf("consent audits: %d", n)
	}
	st, team := h.teamState(admin, inspID)
	if st != "SHARING" {
		t.Fatalf("after start: %s", st)
	}
	for _, e := range team.Engineers {
		if e.UserID == inspID && (e.Lat == nil || *e.Lat != 17.41 || e.Battery == nil || *e.Battery != 79 || e.OnDuty || e.LastContact == nil) {
			t.Fatalf("row: %+v", e)
		}
	}

	// Live: the admin's request reaches the phone with its next upload.
	if st := h.do("POST", "/v1/admin/tracking/live", admin, map[string]string{"user_id": inspID}, nil); st != 200 {
		t.Fatalf("live: %d", st)
	}
	if st := h.do("POST", "/v1/admin/tracking/live", insp, map[string]string{"user_id": inspID}, nil); st != 403 {
		t.Fatalf("inspector asked for live: %d", st)
	}
	h.do("POST", "/v1/tracking/batch", insp, map[string]any{"points": []any{}}, &res)
	if res.LiveUntil == nil || *res.LiveUntil < now+9*60_000 {
		t.Fatalf("live not passed on: %+v", res)
	}

	// Location switched off, then turned off with a reason.
	h.do("POST", "/v1/tracking/batch", insp, map[string]any{"events": []any{trackEvent("LOCATION_OFF", now+1000, nil)}}, nil)
	if st, _ := h.teamState(admin, inspID); st != "LOCATION_OFF" {
		t.Fatalf("location off: %s", st)
	}
	h.do("POST", "/v1/tracking/batch", insp, map[string]any{"events": []any{
		trackEvent("LOCATION_ON", now+2000, nil),
		trackEvent("PAUSE", now+3000, map[string]any{"reason": "LOW_BATTERY", "note": "5 %"}),
	}}, nil)
	st, team = h.teamState(admin, inspID)
	if st != "PAUSED" {
		t.Fatalf("paused: %s", st)
	}
	for _, e := range team.Engineers {
		if e.UserID == inspID && (e.Reason == nil || *e.Reason != "LOW_BATTERY" || e.Note == nil || *e.Note != "5 %") {
			t.Fatalf("pause row: %+v", e)
		}
	}
	h.db.QueryRow(context.Background(), `SELECT count(*) FROM audit_events WHERE type = 'TRACKING_PAUSED'`).Scan(&n)
	if n != 1 {
		t.Fatalf("pause audits: %d", n)
	}

	// Stale: sharing, but the server hasn't heard from the phone for longer than staleAfter.
	h.do("POST", "/v1/tracking/batch", insp, map[string]any{"events": []any{trackEvent("START", now+4000, map[string]any{"source": "MANUAL"})}}, nil)
	h.server.now = func() time.Time { return time.Now().Add(staleAfter + time.Minute) }
	if st, _ := h.teamState(admin, inspID); st != "STALE" {
		t.Fatalf("stale: %s", st)
	}
	h.server.now = time.Now

	// Check-out stops sharing: no alert.
	h.do("POST", "/v1/tracking/batch", insp, map[string]any{"events": []any{trackEvent("STOP", now+5000, map[string]any{"source": "CHECK_OUT"})}}, nil)
	if st, _ := h.teamState(admin, inspID); st != "OFF" {
		t.Fatalf("stopped: %s", st)
	}

	// Day view: route + events, consent hidden.
	var day struct {
		Points []map[string]any `json:"points"`
		Events []struct {
			Kind string `json:"kind"`
		} `json:"events"`
	}
	path := "/v1/admin/tracking/day?user=" + inspID + "&from=" + itoa(now-3600_000) + "&to=" + itoa(now+3600_000)
	if st := h.do("GET", path, admin, nil, &day); st != 200 || len(day.Points) != 2 || len(day.Events) != 6 {
		t.Fatalf("day: %d points=%d events=%+v", st, len(day.Points), day.Events)
	}
	if st := h.do("GET", path, insp, nil, nil); st != 403 {
		t.Fatalf("inspector saw a day: %d", st)
	}
}

func itoa(v int64) string { return strconv.FormatInt(v, 10) }

func TestSharingStateLowBatteryGrace(t *testing.T) {
	now := time.Now()
	start := &lastEvent{Kind: "START", ClientAt: 1}
	contact := now.Add(-25 * time.Minute)
	full, low := 80, 15
	if st := sharingState(start, nil, &contact, &full, now); st.State != "STALE" {
		t.Fatalf("25 min silent, full battery: %s", st.State)
	}
	if st := sharingState(start, nil, &contact, &low, now); st.State != "SHARING" {
		t.Fatalf("25 min silent, low battery (15-min uploads): %s", st.State)
	}
	if st := sharingState(start, nil, nil, nil, now); st.State != "STALE" {
		t.Fatalf("never heard from: %s", st.State)
	}
}

func TestPurgeOldTracking(t *testing.T) {
	h := setup(t)
	h.user("insp", "INSPECTOR")
	insp := h.login("insp")
	now := time.Now().UnixMilli()
	h.do("POST", "/v1/tracking/batch", insp, map[string]any{"points": []any{point(now, 17.4, 78.4, 50)},
		"events": []any{trackEvent("START", now, nil)}}, nil)
	if n, err := h.server.PurgeOldTracking(context.Background()); err != nil || n != 0 {
		t.Fatalf("fresh purge: %d %v", n, err)
	}
	h.server.now = func() time.Time { return time.Now().Add(trackingRetention + time.Hour) }
	defer func() { h.server.now = time.Now }()
	if n, err := h.server.PurgeOldTracking(context.Background()); err != nil || n != 2 {
		t.Fatalf("expired purge: %d %v", n, err)
	}
}
