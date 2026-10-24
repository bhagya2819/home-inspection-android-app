package api

import (
	"context"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
)

// Live location (feature 2). While an engineer shares their location, the phone records points and
// uploads them in batches (every 10 min; every 30 s while an admin watches live). Sharing events
// (started, stopped, turned off with a reason, interrupted, location switched off) come in the same
// batch. Admins see a Team view and each engineer's day; engineers see nothing of it.
// Alerts are in-app only: there is no push yet, so nothing here is instant.

const (
	trackingRetention = 90 * 24 * time.Hour
	liveFor           = 10 * time.Minute
	// No upload from a sharing phone for this long = "No location for N min". The phone uploads
	// every 10 min (15 min on low battery), so one missed upload doesn't raise it.
	staleAfter           = 20 * time.Minute
	staleAfterLowBattery = 30 * time.Minute
	lowBattery           = 20
	maxBatchPoints       = 2000
	maxBatchEvents       = 200
	maxNote              = 300
)

var trackingKinds = map[string]bool{"START": true, "STOP": true, "PAUSE": true, "INTERRUPTED": true,
	"LOCATION_OFF": true, "LOCATION_ON": true, "MOCK": true, "CONSENT": true}

type pointIn struct {
	ID        string   `json:"id"`
	ClientAt  int64    `json:"client_at"`
	Lat       float64  `json:"lat"`
	Lng       float64  `json:"lng"`
	AccuracyM *float32 `json:"accuracy_m"`
	Battery   *int     `json:"battery"`
	IsMock    bool     `json:"is_mock"`
}

func (p pointIn) valid() bool {
	return isUUID(p.ID) && p.ClientAt > 0 && p.Lat >= -90 && p.Lat <= 90 && p.Lng >= -180 && p.Lng <= 180 &&
		!math.IsNaN(p.Lat) && !math.IsNaN(p.Lng) && (p.Battery == nil || (*p.Battery >= 0 && *p.Battery <= 100)) &&
		(p.AccuracyM == nil || (*p.AccuracyM >= 0 && *p.AccuracyM < 1e6))
}

type trackEventIn struct {
	ID       string  `json:"id"`
	Kind     string  `json:"kind"`
	Source   *string `json:"source"`
	Reason   *string `json:"reason"`
	Note     *string `json:"note"`
	ClientAt int64   `json:"client_at"`
}

func short(s *string, max int) bool { return s == nil || len(*s) <= max }

func (e trackEventIn) valid() bool {
	return isUUID(e.ID) && trackingKinds[e.Kind] && e.ClientAt > 0 && short(e.Source, 32) && short(e.Reason, 32) && short(e.Note, maxNote)
}

// trackingBatch: POST /v1/tracking/batch. Invalid rows are skipped (counted), never the whole
// batch, so one bad row can't block a phone's queue. The answer tells the phone whether an admin
// is watching it live.
func (s *Server) trackingBatch(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	var req struct {
		UserID string         `json:"user_id"`
		Points []pointIn      `json:"points"`
		Events []trackEventIn `json:"events"`
	}
	if !readJSON(w, r, 1<<20, &req) {
		return
	}
	if len(req.Points) > maxBatchPoints || len(req.Events) > maxBatchEvents {
		writeError(w, http.StatusRequestEntityTooLarge, "TOO_LARGE", "too many rows in one batch")
		return
	}
	// Same rule as attendance: a shared phone never files one engineer's points under another's session.
	if req.UserID != "" && req.UserID != u.ID {
		writeError(w, http.StatusForbidden, "WRONG_USER", "these points belong to another account; they upload when they sign in")
		return
	}
	now := s.now()
	skipped := 0
	type audit struct{ typ, entity, details string }
	var audits []audit
	err := pgx.BeginFunc(r.Context(), s.db, func(tx pgx.Tx) error {
		b := &pgx.Batch{}
		for _, p := range req.Points {
			if !p.valid() {
				skipped++
				continue
			}
			b.Queue(`INSERT INTO location_points (id, user_id, device_id, client_at, lat, lng, accuracy_m, battery, is_mock, received_at)
				VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) ON CONFLICT (id) DO NOTHING`,
				p.ID, u.ID, u.deviceID, p.ClientAt, p.Lat, p.Lng, p.AccuracyM, p.Battery, p.IsMock, now)
		}
		var events []trackEventIn
		for _, e := range req.Events {
			if !e.valid() {
				skipped++
				continue
			}
			events = append(events, e)
			b.Queue(`INSERT INTO tracking_events (id, user_id, device_id, kind, source, reason, note, client_at, received_at)
				VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) ON CONFLICT (id) DO NOTHING`,
				e.ID, u.ID, u.deviceID, e.Kind, e.Source, e.Reason, e.Note, e.ClientAt, now)
		}
		res := tx.SendBatch(r.Context(), b)
		valid := b.Len() - len(events)
		for i := 0; i < b.Len(); i++ {
			tag, err := res.Exec()
			if err != nil {
				res.Close()
				return err
			}
			if i < valid || tag.RowsAffected() == 0 {
				continue // a point, or a retried event: audited once
			}
			e := events[i-valid]
			switch e.Kind {
			case "PAUSE":
				audits = append(audits, audit{"TRACKING_PAUSED", "TRACKING:" + e.ID, "reason=" + deref(e.Reason)})
			case "INTERRUPTED":
				audits = append(audits, audit{"TRACKING_INTERRUPTED", "TRACKING:" + e.ID, "reason=" + deref(e.Reason)})
			case "CONSENT":
				audits = append(audits, audit{"TRACKING_CONSENT", "TRACKING:" + e.ID, "notice=" + deref(e.Note)})
			}
		}
		return res.Close()
	})
	if err != nil {
		s.internal(w, "tracking batch", err)
		return
	}
	for _, a := range audits {
		s.auditEvent(r.Context(), a.typ, &u.ID, u.deviceID, a.entity, a.details)
	}
	var liveUntil *int64
	var until time.Time
	if err := s.db.QueryRow(r.Context(), `SELECT until FROM tracking_live WHERE user_id = $1`, u.ID).Scan(&until); err == nil && until.After(now) {
		ms := millis(until)
		liveUntil = &ms
	}
	writeJSON(w, http.StatusOK, map[string]any{"skipped": skipped, "live_until": liveUntil, "server_now": millis(now)})
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// ---- Admin: Team view --------------------------------------------------------------------------

type trackState struct {
	State  string  // OFF | SHARING | PAUSED | INTERRUPTED | LOCATION_OFF | STALE
	Since  *int64  // phone time of the event the state comes from
	Source *string // START's source / STOP's
	Reason *string
	Note   *string
}

type lastEvent struct {
	Kind     string
	Source   *string
	Reason   *string
	Note     *string
	ClientAt int64
}

// sharingState decides what the admin sees for one engineer. lastContact is when the server last
// heard from that phone (server clock, immune to a wrong phone clock).
func sharingState(main, loc *lastEvent, lastContact *time.Time, battery *int, now time.Time) trackState {
	if main == nil || main.Kind == "STOP" {
		st := trackState{State: "OFF"}
		if main != nil {
			st.Since, st.Source = &main.ClientAt, main.Source
		}
		return st
	}
	st := trackState{Since: &main.ClientAt, Source: main.Source, Reason: main.Reason, Note: main.Note}
	switch main.Kind {
	case "PAUSE":
		st.State = "PAUSED"
		return st
	case "INTERRUPTED":
		st.State = "INTERRUPTED"
		return st
	}
	st.State = "SHARING"
	if loc != nil && loc.Kind == "LOCATION_OFF" && loc.ClientAt >= main.ClientAt {
		st.State, st.Since = "LOCATION_OFF", &loc.ClientAt
		return st
	}
	limit := staleAfter
	if battery != nil && *battery <= lowBattery {
		limit = staleAfterLowBattery
	}
	if lastContact == nil || now.Sub(*lastContact) > limit {
		st.State = "STALE"
	}
	return st
}

type teamRow struct {
	UserID       string   `json:"user_id"`
	EmployeeCode string   `json:"employee_code"`
	DisplayName  string   `json:"display_name"`
	Role         string   `json:"role"`
	FaceID       *string  `json:"face_id"`     // approved enrolment with a photo: the row's picture
	OnDuty       bool     `json:"on_duty"`     // latest attendance is a check-in
	DutySince    *int64   `json:"duty_since"`  // that check-in/out's time
	State        string   `json:"state"`       // see trackState
	StateSince   *int64   `json:"state_since"` // phone time
	Source       *string  `json:"source"`      // CHECK_IN | MANUAL | RESUME | RESTORE | CHECK_OUT
	Reason       *string  `json:"reason"`      // PAUSE / INTERRUPTED reason
	Note         *string  `json:"note"`        // PAUSE note
	Lat          *float64 `json:"lat"`         // latest point
	Lng          *float64 `json:"lng"`
	AccuracyM    *float32 `json:"accuracy_m"`
	PointAt      *int64   `json:"point_at"`     // phone time of the latest point
	Battery      *int     `json:"battery"`      // at the latest point
	LastContact  *int64   `json:"last_contact"` // server time the phone last uploaded
	MockToday    bool     `json:"mock_today"`   // a fake-location app was seen in the last 24 h
	LiveUntil    *int64   `json:"live_until"`
}

func (s *Server) trackingTeam(w http.ResponseWriter, r *http.Request) {
	now := s.now()
	rows, err := s.db.Query(r.Context(), `
		SELECT u.id::text, u.employee_code, u.display_name, u.role,
		       (SELECT f.id::text FROM face_enrollments f WHERE f.user_id = u.id AND f.status = 'APPROVED' AND f.photo_verified_at IS NOT NULL),
		       a.kind, a.client_at,
		       m.kind, m.source, m.reason, m.note, m.client_at,
		       l.kind, l.client_at,
		       p.lat, p.lng, p.accuracy_m, p.client_at, p.battery,
		       GREATEST((SELECT max(received_at) FROM tracking_events e WHERE e.user_id = u.id AND e.received_at > $1::timestamptz - interval '2 days'),
		                (SELECT max(received_at) FROM location_points q WHERE q.user_id = u.id AND q.received_at > $1::timestamptz - interval '2 days')),
		       EXISTS (SELECT 1 FROM location_points q WHERE q.user_id = u.id AND q.is_mock AND q.received_at > $1::timestamptz - interval '1 day')
		         OR EXISTS (SELECT 1 FROM tracking_events e WHERE e.user_id = u.id AND e.kind = 'MOCK' AND e.received_at > $1::timestamptz - interval '1 day'),
		       t.until
		FROM users u
		LEFT JOIN LATERAL (SELECT kind, client_at FROM attendance WHERE user_id = u.id ORDER BY client_at DESC LIMIT 1) a ON true
		LEFT JOIN LATERAL (SELECT kind, source, reason, note, client_at FROM tracking_events
		                   WHERE user_id = u.id AND kind IN ('START', 'STOP', 'PAUSE', 'INTERRUPTED') ORDER BY client_at DESC LIMIT 1) m ON true
		LEFT JOIN LATERAL (SELECT kind, client_at FROM tracking_events
		                   WHERE user_id = u.id AND kind IN ('LOCATION_OFF', 'LOCATION_ON') ORDER BY client_at DESC LIMIT 1) l ON true
		LEFT JOIN LATERAL (SELECT lat, lng, accuracy_m, client_at, battery FROM location_points
		                   WHERE user_id = u.id ORDER BY client_at DESC LIMIT 1) p ON true
		LEFT JOIN tracking_live t ON t.user_id = u.id AND t.until > $1
		WHERE u.status = 'ACTIVE'
		ORDER BY u.display_name`, now)
	if err != nil {
		s.internal(w, "tracking team", err)
		return
	}
	defer rows.Close()
	list := []teamRow{}
	for rows.Next() {
		var t teamRow
		var duty, mKind, lKind *string
		var mSource, mReason, mNote *string
		var mAt, lAt *int64
		var battery *int16
		var contact, live *time.Time
		if err := rows.Scan(&t.UserID, &t.EmployeeCode, &t.DisplayName, &t.Role, &t.FaceID, &duty, &t.DutySince,
			&mKind, &mSource, &mReason, &mNote, &mAt, &lKind, &lAt,
			&t.Lat, &t.Lng, &t.AccuracyM, &t.PointAt, &battery, &contact, &t.MockToday, &live); err != nil {
			s.internal(w, "tracking team", err)
			return
		}
		t.OnDuty = duty != nil && *duty == "IN"
		if battery != nil {
			b := int(*battery)
			t.Battery = &b
		}
		var main, loc *lastEvent
		if mKind != nil {
			main = &lastEvent{Kind: *mKind, Source: mSource, Reason: mReason, Note: mNote, ClientAt: *mAt}
		}
		if lKind != nil {
			loc = &lastEvent{Kind: *lKind, ClientAt: *lAt}
		}
		st := sharingState(main, loc, contact, t.Battery, now)
		t.State, t.StateSince, t.Source, t.Reason, t.Note = st.State, st.Since, st.Source, st.Reason, st.Note
		if contact != nil {
			c := millis(*contact)
			t.LastContact = &c
		}
		if live != nil {
			l := millis(*live)
			t.LiveUntil = &l
		}
		list = append(list, t)
	}
	if err := rows.Err(); err != nil {
		s.internal(w, "tracking team", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"engineers": list, "server_now": millis(now)})
}

// ---- Admin: one engineer's day ----------------------------------------------------------------

type pointOut struct {
	ClientAt  int64    `json:"client_at"`
	Lat       float64  `json:"lat"`
	Lng       float64  `json:"lng"`
	AccuracyM *float32 `json:"accuracy_m"`
	Battery   *int16   `json:"battery"`
	IsMock    bool     `json:"is_mock"`
}

type trackEventOut struct {
	Kind     string   `json:"kind"`
	Source   *string  `json:"source"`
	Reason   *string  `json:"reason"`
	Note     *string  `json:"note"`
	ClientAt int64    `json:"client_at"`
	Lat      *float64 `json:"lat"` // check-in/out only
	Lng      *float64 `json:"lng"`
}

// trackingDay: GET /v1/admin/tracking/day?user=&from=&to= (epoch ms, at most 2 days): the route,
// the sharing events and the check-ins/outs (as CHECK_IN / CHECK_OUT events) for the timeline.
func (s *Server) trackingDay(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	user := q.Get("user")
	from, err1 := strconv.ParseInt(q.Get("from"), 10, 64)
	to, err2 := strconv.ParseInt(q.Get("to"), 10, 64)
	if !isUUID(user) || err1 != nil || err2 != nil || to <= from || to-from > int64(48*time.Hour/time.Millisecond) {
		writeError(w, http.StatusBadRequest, "INVALID", "user must be a UUID, from/to epoch ms at most 2 days apart")
		return
	}
	points := []pointOut{}
	rows, err := s.db.Query(r.Context(), `SELECT client_at, lat, lng, accuracy_m, battery, is_mock FROM location_points
		WHERE user_id = $1 AND client_at >= $2 AND client_at < $3 ORDER BY client_at LIMIT 20000`, user, from, to)
	if err != nil {
		s.internal(w, "tracking day", err)
		return
	}
	for rows.Next() {
		var p pointOut
		if err := rows.Scan(&p.ClientAt, &p.Lat, &p.Lng, &p.AccuracyM, &p.Battery, &p.IsMock); err != nil {
			rows.Close()
			s.internal(w, "tracking day", err)
			return
		}
		points = append(points, p)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		s.internal(w, "tracking day", err)
		return
	}
	events := []trackEventOut{}
	rows, err = s.db.Query(r.Context(), `
		SELECT kind, source, reason, note, client_at, NULL::double precision, NULL::double precision FROM tracking_events
		WHERE user_id = $1 AND client_at >= $2 AND client_at < $3 AND kind <> 'CONSENT'
		UNION ALL
		SELECT CASE kind WHEN 'IN' THEN 'CHECK_IN' ELSE 'CHECK_OUT' END, NULL, NULL, NULL, client_at, lat, lng FROM attendance
		WHERE user_id = $1 AND client_at >= $2 AND client_at < $3
		ORDER BY 5 LIMIT 2000`, user, from, to)
	if err != nil {
		s.internal(w, "tracking day", err)
		return
	}
	defer rows.Close()
	for rows.Next() {
		var e trackEventOut
		if err := rows.Scan(&e.Kind, &e.Source, &e.Reason, &e.Note, &e.ClientAt, &e.Lat, &e.Lng); err != nil {
			s.internal(w, "tracking day", err)
			return
		}
		events = append(events, e)
	}
	if err := rows.Err(); err != nil {
		s.internal(w, "tracking day", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"points": points, "events": events})
}

// trackingLive: POST /v1/admin/tracking/live {"user_id"} — watch an engineer live for 10 minutes.
// The phone switches at its next upload (up to 10 min later; no push yet).
func (s *Server) trackingLive(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	var req struct {
		UserID string `json:"user_id"`
	}
	if !readJSON(w, r, 4<<10, &req) {
		return
	}
	if !isUUID(req.UserID) {
		writeError(w, http.StatusBadRequest, "INVALID", "user_id must be a UUID")
		return
	}
	until := s.now().Add(liveFor)
	tag, err := s.db.Exec(r.Context(), `INSERT INTO tracking_live (user_id, until, requested_by)
		SELECT id, $2, $3 FROM users WHERE id = $1
		ON CONFLICT (user_id) DO UPDATE SET until = EXCLUDED.until, requested_by = EXCLUDED.requested_by`, req.UserID, until, u.ID)
	if err != nil {
		s.internal(w, "tracking live", err)
		return
	}
	if tag.RowsAffected() == 0 {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such user")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"live_until": millis(until)})
}

// ---- Retention ---------------------------------------------------------------------------------

// PurgeOldTracking deletes location points and sharing events older than trackingRetention (by
// arrival at the server), in chunks. Returns how many rows it removed.
func (s *Server) PurgeOldTracking(ctx context.Context) (int64, error) {
	cutoff := s.now().Add(-trackingRetention)
	var total int64
	for _, table := range []string{"location_points", "tracking_events"} {
		for {
			tag, err := s.db.Exec(ctx, fmt.Sprintf(`DELETE FROM %s WHERE id IN (SELECT id FROM %s WHERE received_at < $1 LIMIT 5000)`, table, table), cutoff)
			if err != nil {
				return total, err
			}
			total += tag.RowsAffected()
			if tag.RowsAffected() < 5000 {
				break
			}
		}
	}
	if _, err := s.db.Exec(ctx, `DELETE FROM tracking_live WHERE until < $1`, s.now()); err != nil {
		return total, err
	}
	if total > 0 {
		s.auditEvent(ctx, "LOCATIONS_PURGED", nil, nil, "", fmt.Sprintf("count=%d", total))
	}
	return total, nil
}

// RunTrackingRetention purges expired location data now and then every 6 hours until ctx ends.
func (s *Server) RunTrackingRetention(ctx context.Context) {
	tick := time.NewTicker(6 * time.Hour)
	defer tick.Stop()
	for {
		if n, err := s.PurgeOldTracking(ctx); err != nil && !errors.Is(err, context.Canceled) {
			s.log.Error("tracking retention", "err", err)
		} else if n > 0 {
			s.log.Info("tracking retention", "removed", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}
