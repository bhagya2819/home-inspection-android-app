package api

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// Leave requests (admin feature). An engineer asks for leave (casual, sick or comp-off; full or
// half day) from the phone; the admin approves or rejects it in the app or on the web. Approved
// leave shows on the attendance calendar. The admin can also record leave directly (an engineer
// who phoned in sick). No yearly balance and no comp-off rules yet (user decision): the comp-off
// balance is shown, never enforced.

var leaveKinds = map[string]bool{"CASUAL": true, "SICK": true, "COMP_OFF": true}

const (
	maxLeaveSpanDays  = 62
	maxLeaveBackdate  = 60 * 24 * time.Hour  // an engineer may ask for leave already taken (sick)
	maxAdminBackdate  = 400 * 24 * time.Hour // the admin may record older leave
	maxLeaveAhead     = 400 * 24 * time.Hour
	maxLeaveReasonLen = 500
)

type leaveIn struct {
	ID       string  `json:"id"`
	UserID   string  `json:"user_id"`
	Kind     string  `json:"kind"`
	StartDay string  `json:"start_day"`
	EndDay   string  `json:"end_day"`
	Half     *string `json:"half"` // FIRST | SECOND, single day only
	Reason   string  `json:"reason"`
}

// validate returns the parsed days, or a message for the 400.
func (in *leaveIn) validate(today time.Time, backdate time.Duration) (start, end time.Time, msg string) {
	in.Reason = strings.TrimSpace(in.Reason)
	start, ok1 := parseDay(in.StartDay)
	end, ok2 := parseDay(in.EndDay)
	switch {
	case !isUUID(in.ID):
		return start, end, "id must be a UUID"
	case !leaveKinds[in.Kind]:
		return start, end, "kind must be CASUAL, SICK or COMP_OFF"
	case !ok1 || !ok2:
		return start, end, "start_day and end_day must be YYYY-MM-DD"
	case end.Before(start):
		return start, end, "end_day is before start_day"
	case end.Sub(start) >= maxLeaveSpanDays*24*time.Hour:
		return start, end, fmt.Sprintf("at most %d days in one request", maxLeaveSpanDays)
	case in.Half != nil && *in.Half != "FIRST" && *in.Half != "SECOND":
		return start, end, "half must be FIRST or SECOND"
	case in.Half != nil && !start.Equal(end):
		return start, end, "a half day is a single day"
	case start.Before(today.Add(-backdate)):
		return start, end, "that date is too far in the past"
	case end.After(today.Add(maxLeaveAhead)):
		return start, end, "that date is too far ahead"
	case len(in.Reason) > maxLeaveReasonLen:
		return start, end, fmt.Sprintf("reason: at most %d characters", maxLeaveReasonLen)
	}
	return start, end, ""
}

type leaveOut struct {
	ID           string  `json:"id"`
	UserID       string  `json:"user_id"`
	EmployeeCode string  `json:"employee_code"`
	DisplayName  string  `json:"display_name"`
	Kind         string  `json:"kind"`
	StartDay     string  `json:"start_day"`
	EndDay       string  `json:"end_day"`
	Half         *string `json:"half"`
	Days         float64 `json:"days"` // working days covered (Sundays/holidays don't count)
	Reason       string  `json:"reason"`
	Status       string  `json:"status"`
	CreatedAt    int64   `json:"created_at"`
	ByAdmin      bool    `json:"by_admin"` // recorded by an admin, not requested by the engineer
	DecidedAt    *int64  `json:"decided_at"`
	DecidedBy    *string `json:"decided_by"` // display name
	DecisionNote string  `json:"decision_note"`
	// Admin list only: days in the range the engineer worked anyway, and (comp-off requests) their
	// comp-off balance before this request counts, so the admin decides knowing both.
	WorkedDays *int            `json:"worked_days,omitempty"`
	CompOff    *compOffBalance `json:"comp_off,omitempty"`
}

// listLeave returns leave requests, newest first. userID "" = everyone; status "" = any.
func (s *Server) listLeave(ctx context.Context, userID, status string, limit int) ([]leaveOut, error) {
	return s.queryLeave(ctx, userID, status, "", limit)
}

func (s *Server) queryLeave(ctx context.Context, userID, status, id string, limit int) ([]leaveOut, error) {
	rows, err := s.db.Query(ctx, `
		SELECT l.id::text, l.user_id::text, u.employee_code, u.display_name, l.kind, l.start_day, l.end_day, l.half, l.reason, l.status,
		       l.created_at, l.created_by <> l.user_id, l.decided_at, d.display_name, l.decision_note
		FROM leave_requests l JOIN users u ON u.id = l.user_id LEFT JOIN users d ON d.id = l.decided_by
		WHERE ($1 = '' OR l.user_id::text = $1) AND ($2 = '' OR l.status = $2) AND ($4 = '' OR l.id::text = $4)
		ORDER BY CASE WHEN l.status = 'PENDING' THEN 0 ELSE 1 END, l.start_day DESC, l.created_at DESC
		LIMIT $3`, userID, status, limit, id)
	if err != nil {
		return nil, err
	}
	type raw struct {
		o          leaveOut
		start, end time.Time
		created    time.Time
		decided    *time.Time
	}
	var list []raw
	minDay, maxDay := time.Time{}, time.Time{}
	for rows.Next() {
		var x raw
		if err := rows.Scan(&x.o.ID, &x.o.UserID, &x.o.EmployeeCode, &x.o.DisplayName, &x.o.Kind, &x.start, &x.end, &x.o.Half, &x.o.Reason,
			&x.o.Status, &x.created, &x.o.ByAdmin, &x.decided, &x.o.DecidedBy, &x.o.DecisionNote); err != nil {
			rows.Close()
			return nil, err
		}
		if minDay.IsZero() || x.start.Before(minDay) {
			minDay = x.start
		}
		if x.end.After(maxDay) {
			maxDay = x.end
		}
		list = append(list, x)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	holidays := map[string]string{}
	if len(list) > 0 {
		if holidays, err = s.holidaysBetween(ctx, minDay, maxDay); err != nil {
			return nil, err
		}
	}
	out := make([]leaveOut, 0, len(list))
	for _, x := range list {
		x.o.StartDay, x.o.EndDay = x.start.Format(dayLayout), x.end.Format(dayLayout)
		x.o.Days = leaveDays(x.start, x.end, x.o.Half, holidays)
		x.o.CreatedAt = millis(x.created)
		if x.decided != nil {
			t := millis(*x.decided)
			x.o.DecidedAt = &t
		}
		out = append(out, x.o)
	}
	return out, nil
}

// overlapping: the status of another PENDING or APPROVED request of this user covering any of
// these days ("" if none; APPROVED wins over PENDING).
func overlapping(ctx context.Context, tx pgx.Tx, userID, id string, start, end time.Time) (string, error) {
	var status string
	err := tx.QueryRow(ctx, `SELECT status FROM leave_requests WHERE user_id = $1 AND id <> $2 AND status IN ('PENDING', 'APPROVED')
		AND start_day <= $4 AND end_day >= $3 ORDER BY status LIMIT 1`, userID, id, start, end).Scan(&status)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil
	}
	return status, err
}

var (
	errOverlap        = errors.New("overlap")
	errOverlapPending = errors.New("overlaps a pending request")
	errIDTaken        = errors.New("id taken")
)

// insertLeave stores a request. Retrying the same id is a no-op for its owner (200, existing row).
func (s *Server) insertLeave(ctx context.Context, in leaveIn, start, end time.Time, userID string, by *authUser, approved bool) (status int, code, msg string) {
	err := pgx.BeginFunc(ctx, s.db, func(tx pgx.Tx) error {
		// Serialise one user's requests so two phones can't both pass the overlap check.
		if _, err := tx.Exec(ctx, `SELECT 1 FROM users WHERE id = $1 FOR UPDATE`, userID); err != nil {
			return err
		}
		var owner string
		err := tx.QueryRow(ctx, `SELECT user_id::text FROM leave_requests WHERE id = $1`, in.ID).Scan(&owner)
		if err == nil {
			if owner != userID {
				return errIDTaken
			}
			return nil // retry
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		if o, err := overlapping(ctx, tx, userID, in.ID, start, end); err != nil {
			return err
		} else if o == "PENDING" && approved {
			return errOverlapPending
		} else if o != "" {
			return errOverlap
		}
		st := "PENDING"
		var decidedBy *string
		var decidedAt *time.Time
		if approved {
			st, decidedBy = "APPROVED", &by.ID
			now := s.now()
			decidedAt = &now
		}
		_, err = tx.Exec(ctx, `INSERT INTO leave_requests (id, user_id, device_id, kind, start_day, end_day, half, reason, status, created_by, decided_at, decided_by)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
			in.ID, userID, by.deviceID, in.Kind, start, end, in.Half, in.Reason, st, by.ID, decidedAt, decidedBy)
		return err
	})
	switch {
	case errors.Is(err, errIDTaken):
		return http.StatusConflict, "CONFLICT", "id already used"
	case errors.Is(err, errOverlapPending):
		return http.StatusConflict, "OVERLAP", "this engineer has a pending request for these days: approve it under Leave requests instead"
	case errors.Is(err, errOverlap) && approved:
		return http.StatusConflict, "OVERLAP", "this engineer already has approved leave on one of these days"
	case errors.Is(err, errOverlap):
		return http.StatusConflict, "OVERLAP", "you already have leave on one of these days"
	case err != nil:
		s.log.Error("leave", "err", err)
		return http.StatusInternalServerError, "INTERNAL", "server error; try again later"
	}
	return http.StatusOK, "", ""
}

// coversWorkingDay refuses (400) a request that covers only Sundays and holidays: it would do nothing.
func (s *Server) coversWorkingDay(w http.ResponseWriter, r *http.Request, start, end time.Time, half *string) bool {
	holidays, err := s.holidaysBetween(r.Context(), start, end)
	if err != nil {
		s.internal(w, "leave", err)
		return false
	}
	if leaveDays(start, end, half, holidays) == 0 {
		writeError(w, http.StatusBadRequest, "NO_WORKING_DAYS", "these days are already off (Sunday or holiday)")
		return false
	}
	return true
}

// POST /v1/leave — the engineer asks for leave.
func (s *Server) requestLeave(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	var in leaveIn
	if !readJSON(w, r, 8<<10, &in) {
		return
	}
	if in.UserID != "" && in.UserID != u.ID { // same rule as attendance on a shared phone
		writeError(w, http.StatusForbidden, "WRONG_USER", "this request belongs to another account; it uploads when they sign in")
		return
	}
	start, end, msg := in.validate(s.today(), maxLeaveBackdate)
	if msg != "" {
		writeError(w, http.StatusBadRequest, "INVALID", msg)
		return
	}
	if !s.coversWorkingDay(w, r, start, end, in.Half) {
		return
	}
	if st, code, msg := s.insertLeave(r.Context(), in, start, end, u.ID, u, false); st != http.StatusOK {
		writeError(w, st, code, msg)
		return
	}
	s.auditEvent(r.Context(), "LEAVE_REQUESTED", &u.ID, u.deviceID, "LEAVE:"+in.ID, in.Kind+" "+in.StartDay+".."+in.EndDay)
	s.writeLeave(w, r, in.ID)
}

// POST /v1/admin/leave — the admin records approved leave for an engineer.
func (s *Server) adminRecordLeave(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	var in leaveIn
	if !readJSON(w, r, 8<<10, &in) {
		return
	}
	if !isUUID(in.UserID) {
		writeError(w, http.StatusBadRequest, "INVALID", "user_id must be a UUID")
		return
	}
	start, end, msg := in.validate(s.today(), maxAdminBackdate)
	if msg != "" {
		writeError(w, http.StatusBadRequest, "INVALID", msg)
		return
	}
	if !s.coversWorkingDay(w, r, start, end, in.Half) {
		return
	}
	var exists bool
	if err := s.db.QueryRow(r.Context(), `SELECT EXISTS (SELECT 1 FROM users WHERE id = $1)`, in.UserID).Scan(&exists); err != nil {
		s.internal(w, "leave", err)
		return
	}
	if !exists {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such user")
		return
	}
	if st, code, msg := s.insertLeave(r.Context(), in, start, end, in.UserID, u, true); st != http.StatusOK {
		writeError(w, st, code, msg)
		return
	}
	s.auditEvent(r.Context(), "LEAVE_RECORDED", &u.ID, u.deviceID, "LEAVE:"+in.ID, "for="+in.UserID+" "+in.Kind+" "+in.StartDay+".."+in.EndDay)
	s.writeLeave(w, r, in.ID)
}

func (s *Server) writeLeave(w http.ResponseWriter, r *http.Request, id string) {
	list, err := s.queryLeave(r.Context(), "", "", id, 1)
	if err != nil {
		s.internal(w, "leave", err)
		return
	}
	if len(list) == 0 {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such leave request")
		return
	}
	writeJSON(w, http.StatusOK, list[0])
}

// GET /v1/leave — the caller's own requests plus their comp-off balance.
func (s *Server) myLeave(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	list, err := s.listLeave(r.Context(), u.ID, "", 200)
	if err != nil {
		s.internal(w, "leave", err)
		return
	}
	bal, err := s.compOffBalance(r.Context(), u.ID)
	if err != nil {
		s.internal(w, "leave", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"requests": list, "comp_off": bal, "today": s.today().Format(dayLayout)})
}

// GET /v1/admin/leave?status=PENDING&user=<id> — requests for the admin, pending first.
func (s *Server) adminLeave(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	user, status := q.Get("user"), q.Get("status")
	if user != "" && !isUUID(user) {
		writeError(w, http.StatusBadRequest, "INVALID", "user must be a UUID")
		return
	}
	switch status {
	case "", "PENDING", "APPROVED", "REJECTED", "CANCELLED", "REVOKED":
	default:
		writeError(w, http.StatusBadRequest, "INVALID", "unknown status")
		return
	}
	limit := 300
	if n, err := strconv.Atoi(q.Get("limit")); err == nil && n > 0 && n < limit {
		limit = n
	}
	list, err := s.listLeave(r.Context(), user, status, limit)
	if err != nil {
		s.internal(w, "admin leave", err)
		return
	}
	if err := s.annotateLeave(r.Context(), list); err != nil {
		s.internal(w, "admin leave", err)
		return
	}
	var pending int
	if err := s.db.QueryRow(r.Context(), `SELECT count(*) FROM leave_requests WHERE status = 'PENDING'`).Scan(&pending); err != nil {
		s.internal(w, "admin leave", err)
		return
	}
	body := map[string]any{"requests": list, "pending": pending}
	if user != "" {
		bal, err := s.compOffBalance(r.Context(), user)
		if err != nil {
			s.internal(w, "admin leave", err)
			return
		}
		body["comp_off"] = bal
	}
	writeJSON(w, http.StatusOK, body)
}

// annotateLeave adds what the admin should see before deciding: the days of each request the
// engineer worked anyway (a sick day they actually worked), and for comp-off requests the
// engineer's comp-off balance (it may go negative: no rules yet, but never unseen).
func (s *Server) annotateLeave(ctx context.Context, list []leaveOut) error {
	type span struct{ from, to time.Time }
	spans := map[string]*span{}
	for _, l := range list {
		start, _ := parseDay(l.StartDay)
		end, _ := parseDay(l.EndDay)
		sp := spans[l.UserID]
		if sp == nil {
			spans[l.UserID] = &span{start, end}
			continue
		}
		if start.Before(sp.from) {
			sp.from = start
		}
		if end.After(sp.to) {
			sp.to = end
		}
	}
	work := map[string]map[string]*dayWork{}
	for uid, sp := range spans {
		evs, err := s.loadEvents(ctx, []string{uid}, dayStartMs(sp.from.AddDate(0, 0, -1)), dayStartMs(sp.to.AddDate(0, 0, 2)))
		if err != nil {
			return err
		}
		work[uid] = workByDay(evs[uid])
	}
	balances := map[string]*compOffBalance{}
	for i := range list {
		l := &list[i]
		start, _ := parseDay(l.StartDay)
		end, _ := parseDay(l.EndDay)
		n := 0
		for d := start; !d.After(end); d = d.AddDate(0, 0, 1) {
			if work[l.UserID][d.Format(dayLayout)].any() {
				n++
			}
		}
		l.WorkedDays = &n
		if l.Kind == "COMP_OFF" {
			if balances[l.UserID] == nil {
				b, err := s.compOffBalance(ctx, l.UserID)
				if err != nil {
					return err
				}
				balances[l.UserID] = &b
			}
			l.CompOff = balances[l.UserID]
		}
	}
	return nil
}

// POST /v1/admin/leave/{id}/decision {approve, note}.
//
//	PENDING            → APPROVED (approve) | REJECTED
//	APPROVED           → REVOKED (taken back, e.g. the engineer came to work after all)
//	REJECTED / REVOKED → APPROVED (a mis-tap undone)
//
// CANCELLED (the engineer withdrew it) is final. An admin can't decide their own request while
// another admin could.
func (s *Server) decideLeave(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	id := r.PathValue("id")
	var req struct {
		Approve bool   `json:"approve"`
		Note    string `json:"note"`
	}
	if !isUUID(id) {
		writeError(w, http.StatusBadRequest, "INVALID", "id must be a UUID")
		return
	}
	if !readJSON(w, r, 4<<10, &req) {
		return
	}
	req.Note = strings.TrimSpace(req.Note)
	if len(req.Note) > maxLeaveReasonLen {
		writeError(w, http.StatusBadRequest, "INVALID", "note is too long")
		return
	}
	var status, owner string
	var start, end time.Time
	self := false
	err := pgx.BeginFunc(r.Context(), s.db, func(tx pgx.Tx) error {
		if err := tx.QueryRow(r.Context(), `SELECT user_id::text FROM leave_requests WHERE id = $1`, id).Scan(&owner); err != nil {
			return err
		}
		// Same lock as insertLeave: an approval and a new request can't both pass the overlap check.
		if _, err := tx.Exec(r.Context(), `SELECT 1 FROM users WHERE id = $1 FOR UPDATE`, owner); err != nil {
			return err
		}
		if err := tx.QueryRow(r.Context(), `SELECT status, start_day, end_day FROM leave_requests WHERE id = $1 FOR UPDATE`, id).
			Scan(&status, &start, &end); err != nil {
			return err
		}
		var next string
		switch {
		case req.Approve && (status == "PENDING" || status == "REJECTED" || status == "REVOKED"):
			next = "APPROVED"
		case !req.Approve && status == "PENDING":
			next = "REJECTED"
		case !req.Approve && status == "APPROVED":
			next = "REVOKED"
		default:
			return errBadState
		}
		if owner == u.ID {
			var others int
			if err := tx.QueryRow(r.Context(), `SELECT count(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE' AND id <> $1`, u.ID).Scan(&others); err != nil {
				return err
			}
			if others > 0 {
				return errSelfDecision
			}
			self = true
		}
		if next == "APPROVED" {
			if o, err := overlapping(r.Context(), tx, owner, id, start, end); err != nil {
				return err
			} else if o != "" {
				return errOverlap
			}
		}
		_, err := tx.Exec(r.Context(), `UPDATE leave_requests SET status = $2, decided_at = now(), decided_by = $3, decision_note = $4 WHERE id = $1`,
			id, next, u.ID, req.Note)
		status = next
		return err
	})
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such leave request")
		return
	case errors.Is(err, errBadState):
		writeError(w, http.StatusConflict, "ALREADY_DECIDED", "this request is "+strings.ToLower(status)+"; refresh")
		return
	case errors.Is(err, errSelfDecision):
		writeError(w, http.StatusForbidden, "SELF_DECISION", "another admin must decide your own leave")
		return
	case errors.Is(err, errOverlap):
		writeError(w, http.StatusConflict, "OVERLAP", "this engineer already has leave (approved or requested) on one of these days")
		return
	case err != nil:
		s.internal(w, "decide leave", err)
		return
	}
	details := "for=" + owner
	if self {
		details += " self-decided (only admin)"
	}
	s.auditEvent(r.Context(), "LEAVE_"+status, &u.ID, u.deviceID, "LEAVE:"+id, details)
	s.writeLeave(w, r, id)
}

var errSelfDecision = errors.New("self decision")

var errBadState = errors.New("bad state")

// POST /v1/leave/{id}/cancel — the engineer withdraws a request: any pending one, or approved
// leave that hasn't started yet.
func (s *Server) cancelLeave(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	id := r.PathValue("id")
	if !isUUID(id) {
		writeError(w, http.StatusBadRequest, "INVALID", "id must be a UUID")
		return
	}
	var status, owner string
	var start time.Time
	err := s.db.QueryRow(r.Context(), `SELECT status, user_id::text, start_day FROM leave_requests WHERE id = $1`, id).Scan(&status, &owner, &start)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && owner != u.ID) {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such leave request")
		return
	}
	if err != nil {
		s.internal(w, "cancel leave", err)
		return
	}
	if status == "CANCELLED" {
		s.writeLeave(w, r, id) // retry
		return
	}
	if !(status == "PENDING" || (status == "APPROVED" && start.After(s.today()))) {
		writeError(w, http.StatusConflict, "CANNOT_CANCEL", "only pending requests or leave that hasn't started can be cancelled")
		return
	}
	tag, err := s.db.Exec(r.Context(), `UPDATE leave_requests SET status = 'CANCELLED', cancelled_at = now() WHERE id = $1 AND status = $2`, id, status)
	if err != nil {
		s.internal(w, "cancel leave", err)
		return
	}
	if tag.RowsAffected() == 0 {
		writeError(w, http.StatusConflict, "ALREADY_DECIDED", "this request changed meanwhile; refresh")
		return
	}
	s.auditEvent(r.Context(), "LEAVE_CANCELLED", &u.ID, u.deviceID, "LEAVE:"+id, "")
	s.writeLeave(w, r, id)
}

// ---- Holidays and comp-off overrides ------------------------------------------------------------

// GET /v1/holidays?year=YYYY — any signed-in user (the leave form counts working days with them).
func (s *Server) listHolidays(w http.ResponseWriter, r *http.Request) {
	year, err := strconv.Atoi(r.URL.Query().Get("year"))
	if err != nil || year < 2020 || year > 2100 {
		writeError(w, http.StatusBadRequest, "INVALID", "year must be YYYY")
		return
	}
	m, err := s.holidaysBetween(r.Context(), time.Date(year, 1, 1, 0, 0, 0, 0, time.UTC), time.Date(year, 12, 31, 0, 0, 0, 0, time.UTC))
	if err != nil {
		s.internal(w, "holidays", err)
		return
	}
	list := []map[string]string{}
	for d := time.Date(year, 1, 1, 0, 0, 0, 0, time.UTC); d.Year() == year; d = d.AddDate(0, 0, 1) {
		if name, ok := m[d.Format(dayLayout)]; ok {
			list = append(list, map[string]string{"day": d.Format(dayLayout), "name": name})
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"holidays": list, "weekly_off": []string{"SUNDAY"}})
}

// PUT /v1/admin/holidays/{day} {name} → {day, name, affects}
func (s *Server) putHoliday(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	day, ok := parseDay(r.PathValue("day"))
	var req struct {
		Name string `json:"name"`
	}
	if !ok || day.Year() < 2020 || day.Year() > 2100 {
		writeError(w, http.StatusBadRequest, "INVALID", "day must be YYYY-MM-DD (2020–2100)")
		return
	}
	if !readJSON(w, r, 2<<10, &req) {
		return
	}
	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" || len(req.Name) > 100 {
		writeError(w, http.StatusBadRequest, "INVALID", "name: 1–100 characters")
		return
	}
	if _, err := s.db.Exec(r.Context(), `INSERT INTO holidays (day, name, created_by) VALUES ($1, $2, $3)
		ON CONFLICT (day) DO UPDATE SET name = EXCLUDED.name`, day, req.Name, u.ID); err != nil {
		s.internal(w, "holiday", err)
		return
	}
	s.auditEvent(r.Context(), "HOLIDAY_SET", &u.ID, u.deviceID, "HOLIDAY:"+day.Format(dayLayout), req.Name)
	affects, err := s.holidayAffects(r.Context(), day)
	if err != nil {
		s.internal(w, "holiday", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"day": day.Format(dayLayout), "name": req.Name, "affects": affects})
}

// holidayAffects counts the people whose past records a holiday on day changes: who worked that
// day (their comp-offs change) or has approved or requested leave on it (their leave days change).
// Nothing is rewritten — the calendar is derived — but the admin is told.
func (s *Server) holidayAffects(ctx context.Context, day time.Time) (int, error) {
	var n int
	err := s.db.QueryRow(ctx, `SELECT count(DISTINCT uid) FROM (
		SELECT user_id AS uid FROM attendance WHERE `+eventTimeSQL+` >= $1 AND `+eventTimeSQL+` < $2
		UNION SELECT user_id FROM leave_requests WHERE status IN ('APPROVED', 'PENDING') AND start_day <= $3 AND end_day >= $3) x`,
		dayStartMs(day), dayStartMs(day.AddDate(0, 0, 1)), day).Scan(&n)
	return n, err
}

// DELETE /v1/admin/holidays/{day} → {day, affects}
func (s *Server) deleteHoliday(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	day, ok := parseDay(r.PathValue("day"))
	if !ok {
		writeError(w, http.StatusBadRequest, "INVALID", "day must be YYYY-MM-DD")
		return
	}
	if _, err := s.db.Exec(r.Context(), `DELETE FROM holidays WHERE day = $1`, day); err != nil {
		s.internal(w, "holiday", err)
		return
	}
	s.auditEvent(r.Context(), "HOLIDAY_REMOVED", &u.ID, u.deviceID, "HOLIDAY:"+day.Format(dayLayout), "")
	affects, err := s.holidayAffects(r.Context(), day)
	if err != nil {
		s.internal(w, "holiday", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"day": day.Format(dayLayout), "affects": affects})
}

// POST /v1/admin/comp-offs {user_id, day, action: GRANT | REVOKE | CLEAR, note}
// GRANT: a comp-off for a day without a check-in. REVOKE: take back an automatic one (e.g. a
// ten-minute visit on a Sunday). CLEAR: back to the automatic rule.
func (s *Server) setCompOff(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	var req struct {
		UserID string `json:"user_id"`
		Day    string `json:"day"`
		Action string `json:"action"`
		Note   string `json:"note"`
	}
	if !readJSON(w, r, 4<<10, &req) {
		return
	}
	day, ok := parseDay(req.Day)
	req.Note = strings.TrimSpace(req.Note)
	req.UserID = strings.ToLower(req.UserID) // UUIDs compare as text below (the own-comp-off check)
	if !isUUID(req.UserID) || !ok || (req.Action != "GRANT" && req.Action != "REVOKE" && req.Action != "CLEAR") || len(req.Note) > maxLeaveReasonLen {
		writeError(w, http.StatusBadRequest, "INVALID", "user_id, day (YYYY-MM-DD) and action GRANT | REVOKE | CLEAR are required")
		return
	}
	// Same rule as deciding leave: an admin's own comp-offs need another admin, unless they are the only one.
	self := false
	if req.UserID == u.ID {
		var others int
		if err := s.db.QueryRow(r.Context(), `SELECT count(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE' AND id <> $1`, u.ID).Scan(&others); err != nil {
			s.internal(w, "comp-off", err)
			return
		}
		if others > 0 {
			writeError(w, http.StatusForbidden, "SELF_DECISION", "another admin must change your own comp-offs")
			return
		}
		self = true
	}
	var err error
	if req.Action == "CLEAR" {
		_, err = s.db.Exec(r.Context(), `DELETE FROM comp_off_overrides WHERE user_id = $1 AND day = $2`, req.UserID, day)
	} else {
		_, err = s.db.Exec(r.Context(), `INSERT INTO comp_off_overrides (user_id, day, action, note, created_by) VALUES ($1, $2, $3, $4, $5)
			ON CONFLICT (user_id, day) DO UPDATE SET action = EXCLUDED.action, note = EXCLUDED.note, created_by = EXCLUDED.created_by, created_at = now()`,
			req.UserID, day, req.Action, req.Note, u.ID)
	}
	if err != nil {
		if strings.Contains(err.Error(), "foreign key") {
			writeError(w, http.StatusNotFound, "NOT_FOUND", "no such user")
			return
		}
		s.internal(w, "comp-off", err)
		return
	}
	details := req.Day
	if self {
		details += " self-decided (only admin)"
	}
	s.auditEvent(r.Context(), "COMP_OFF_"+req.Action, &u.ID, u.deviceID, "USER:"+req.UserID, details)
	bal, err := s.compOffBalance(r.Context(), req.UserID)
	if err != nil {
		s.internal(w, "comp-off", err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"comp_off": bal})
}
