package api

import (
	"context"
	"net/http"
	"slices"
	"time"
)

// Attendance calendar (admin feature). Each day of a month gets one status, derived on request
// from check-ins, approved leave, Sundays and holidays — nothing stores it, so a late upload or a
// new holiday changes the picture at once.
//
//   WORKED           worked, and every check-in was followed by a check-out       (green)
//   MISSED_CHECKOUT  checked in but never checked out (a past day)                (yellow square)
//   MISSED           a past working day with no check-in and no leave             (yellow)
//   LEAVE            approved full-day leave; leave.kind COMP_OFF = comp-off taken (red / blue ring)
//   WORKED_ON_LEAVE  approved full-day leave, but worked anyway: counts as worked, not as leave
//   HALF_LEAVE       approved half-day leave; half_work says what the other half was:
//                    WORKED | MISSED | MISSED_CHECKOUT | "" (today, future, or an off day)
//   OFF              Sunday or holiday without work                               (grey)
//   TODAY            today, nothing recorded yet / still checked in
//   FUTURE, NONE     after today / not tracked that day (before attendance_from, after leaving,
//                    or an account the admin doesn't track)                       (blank)
//
// Days are local dates in India time. India has no daylight saving, so a fixed +05:30 zone is
// exact and needs no time-zone database in the scratch image.
//
// Event times are the server's own estimate (attendance.estimated_at, from the phone's monotonic
// clock) when there is one, else the phone's clock. A shift may cross midnight: an IN is closed
// by the next OUT up to maxShift later, and the time is split at midnight between the days.

var companyZone = time.FixedZone("IST", 5*3600+30*60)

const (
	dayLayout   = "2006-01-02"
	monthLayout = "2006-01"
	// Longest check-in → check-out a shift may last; an OUT later than that doesn't close the IN.
	maxShift = 16 * time.Hour
	// Phone clock and server estimate further apart than this: the day is marked clock_skew.
	maxClockSkew = 5 * time.Minute
	// A comp-off earned with less work than this is flagged for the admin to check.
	shortCompOff = time.Hour
)

func localDay(ms int64) string { return time.UnixMilli(ms).In(companyZone).Format(dayLayout) }

// parseDay returns the date at UTC midnight, which keeps AddDate/Weekday arithmetic exact.
func parseDay(s string) (time.Time, bool) {
	t, err := time.Parse(dayLayout, s)
	return t, err == nil
}

// dayStartMs is the first millisecond of day d (UTC-midnight date) in company time.
func dayStartMs(d time.Time) int64 {
	return time.Date(d.Year(), d.Month(), d.Day(), 0, 0, 0, 0, companyZone).UnixMilli()
}

type calLeave struct {
	ID     string  `json:"id"`
	Kind   string  `json:"kind"`
	Half   *string `json:"half"`
	Status string  `json:"status"`
	Reason string  `json:"reason"`
}

type calDay struct {
	Day          string    `json:"day"`
	Status       string    `json:"status"`
	HalfWork     string    `json:"half_work,omitempty"` // HALF_LEAVE only: the other half
	OffDay       bool      `json:"off_day"`
	Holiday      string    `json:"holiday,omitempty"`
	FirstIn      *int64    `json:"first_in"`
	LastOut      *int64    `json:"last_out"`
	WorkedMs     int64     `json:"worked_ms"`          // sum of check-in → check-out time on this day
	Overnight    bool      `json:"overnight"`          // part of a shift that began the day before
	ClockSkew    bool      `json:"clock_skew"`         // the phone's clock was off by more than 5 minutes
	Leave        *calLeave `json:"leave"`              // approved leave covering this day
	PendingLeave *calLeave `json:"pending_leave"`      // a request still waiting for the admin
	CompOff      string    `json:"comp_off,omitempty"` // EARNED (auto) | GRANTED (admin) | REVOKED (admin took it back)
	// EARNED only: why the admin may want to look at it. SHORT = under an hour of work (or no
	// check-out), CLOCK = the phone's clock was off.
	CompOffCheck string `json:"comp_off_check,omitempty"`
}

type calTotals struct {
	DaysWorked      int                `json:"days_worked"`
	WorkedMs        int64              `json:"worked_ms"`
	WorkingDays     int                `json:"working_days"` // past (and today) tracked non-off days
	Missed          int                `json:"missed"`       // incl. the other half of a half-day leave
	MissedCheckouts int                `json:"missed_checkouts"`
	LeaveDays       float64            `json:"leave_days"`
	LeaveByKind     map[string]float64 `json:"leave_by_kind"`
	WorkedOnLeave   int                `json:"worked_on_leave"`
	CompOffsEarned  int                `json:"comp_offs_earned"`
}

type calUser struct {
	ID              string  `json:"id"`
	EmployeeCode    string  `json:"employee_code"`
	DisplayName     string  `json:"display_name"`
	Role            string  `json:"role"`
	Status          string  `json:"status"`
	TrackAttendance bool    `json:"track_attendance"`
	AttendanceFrom  string  `json:"attendance_from"`
	LeftOn          *string `json:"left_on"` // the day the account was disabled
}

// tracked: whether a missing check-in on day d counts as missed.
func (u calUser) tracked(d time.Time) bool {
	if !u.TrackAttendance {
		return false
	}
	if from, ok := parseDay(u.AttendanceFrom); ok && d.Before(from) {
		return false
	}
	if u.LeftOn != nil {
		if left, ok := parseDay(*u.LeftOn); ok && d.After(left) {
			return false
		}
	}
	return true
}

type event struct {
	kind string
	at   int64
	skew bool
}

type leaveRow struct {
	calLeave
	userID     string
	start, end time.Time
}

// dayWork is what one user did on one day, after pairing their check-ins and check-outs.
type dayWork struct {
	firstIn, lastOut *int64
	worked           int64
	open             bool // a check-in on this day was never closed
	overnight        bool
	skew             bool
}

func (w *dayWork) any() bool { return w != nil && (w.firstIn != nil || w.worked > 0) }

// workByDay pairs one user's events (in time order) into days. A second IN while already in is
// ignored (the first one counts) unless the first is older than maxShift; an OUT without an IN,
// or later than maxShift after it, is ignored. Time is split at midnight between the days it
// covers, so a Saturday-night shift that ends on Sunday puts its Sunday minutes on Sunday.
func workByDay(evs []event) map[string]*dayWork {
	out := map[string]*dayWork{}
	get := func(day string) *dayWork {
		if out[day] == nil {
			out[day] = &dayWork{}
		}
		return out[day]
	}
	var inAt int64
	in := false
	for _, e := range evs {
		if e.skew {
			get(localDay(e.at)).skew = true
		}
		switch {
		case e.kind == "IN" && in && time.Duration(e.at-inAt)*time.Millisecond > maxShift:
			get(localDay(inAt)).open = true // the old one was never closed
			fallthrough
		case e.kind == "IN" && !in:
			in, inAt = true, e.at
			w := get(localDay(e.at))
			if w.firstIn == nil {
				t := e.at
				w.firstIn = &t
			}
		case e.kind == "OUT" && in:
			in = false
			if time.Duration(e.at-inAt)*time.Millisecond > maxShift {
				get(localDay(inAt)).open = true
				continue
			}
			startDay := localDay(inAt)
			for t := inAt; t < e.at; {
				d, _ := parseDay(localDay(t))
				end := min(e.at, dayStartMs(d.AddDate(0, 0, 1)))
				w := get(localDay(t))
				w.worked += end - t
				if localDay(t) != startDay {
					w.overnight = true
				}
				t = end
			}
			t := e.at
			get(localDay(e.at)).lastOut = &t
		}
	}
	if in {
		get(localDay(inAt)).open = true
	}
	return out
}

// calendarData is everything needed to colour the days from..to (inclusive) for some users.
type calendarData struct {
	work      map[string]map[string]*dayWork // user → day → work
	leaves    map[string][]leaveRow          // user → approved and pending leave overlapping the range
	holidays  map[string]string              // day → name
	overrides map[string]map[string]string   // user → day → GRANT | REVOKE
}

// eventTimeSQL is when an attendance event happened: the server's estimate, else the phone's clock.
const eventTimeSQL = `COALESCE((extract(epoch FROM estimated_at) * 1000)::bigint, client_at)`

// loadEvents returns each user's events between fromMs and toMs (by event time), in time order.
func (s *Server) loadEvents(ctx context.Context, userIDs []string, fromMs, toMs int64) (map[string][]event, error) {
	rows, err := s.db.Query(ctx, `SELECT user_id::text, kind, client_at, `+eventTimeSQL+` AS at FROM attendance
		WHERE user_id::text = ANY($1) AND `+eventTimeSQL+` >= $2 AND `+eventTimeSQL+` < $3 ORDER BY at`,
		userIDs, fromMs, toMs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string][]event{}
	for rows.Next() {
		var uid string
		var e event
		var client int64
		if err := rows.Scan(&uid, &e.kind, &client, &e.at); err != nil {
			return nil, err
		}
		skew := time.Duration(client-e.at) * time.Millisecond
		e.skew = skew > maxClockSkew || skew < -maxClockSkew
		out[uid] = append(out[uid], e)
	}
	return out, rows.Err()
}

func (s *Server) loadCalendar(ctx context.Context, userIDs []string, from, to time.Time) (*calendarData, error) {
	cd := &calendarData{work: map[string]map[string]*dayWork{}, leaves: map[string][]leaveRow{},
		holidays: map[string]string{}, overrides: map[string]map[string]string{}}
	// A day earlier and later than the range, so shifts across the month's edges pair correctly.
	evs, err := s.loadEvents(ctx, userIDs, dayStartMs(from.AddDate(0, 0, -1)), dayStartMs(to.AddDate(0, 0, 2)))
	if err != nil {
		return nil, err
	}
	for uid, e := range evs {
		cd.work[uid] = workByDay(e)
	}

	rows, err := s.db.Query(ctx, `SELECT id::text, user_id::text, kind, start_day, end_day, half, status, reason FROM leave_requests
		WHERE user_id::text = ANY($1) AND status IN ('APPROVED', 'PENDING') AND start_day <= $3 AND end_day >= $2
		ORDER BY created_at`, userIDs, from, to)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var l leaveRow
		if err := rows.Scan(&l.ID, &l.userID, &l.Kind, &l.start, &l.end, &l.Half, &l.Status, &l.Reason); err != nil {
			rows.Close()
			return nil, err
		}
		cd.leaves[l.userID] = append(cd.leaves[l.userID], l)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}

	if cd.holidays, err = s.holidaysBetween(ctx, from, to); err != nil {
		return nil, err
	}

	rows, err = s.db.Query(ctx, `SELECT user_id::text, day, action FROM comp_off_overrides
		WHERE user_id::text = ANY($1) AND day BETWEEN $2 AND $3`, userIDs, from, to)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var uid, action string
		var day time.Time
		if err := rows.Scan(&uid, &day, &action); err != nil {
			rows.Close()
			return nil, err
		}
		if cd.overrides[uid] == nil {
			cd.overrides[uid] = map[string]string{}
		}
		cd.overrides[uid][day.Format(dayLayout)] = action
	}
	rows.Close()
	return cd, rows.Err()
}

func (s *Server) holidaysBetween(ctx context.Context, from, to time.Time) (map[string]string, error) {
	out := map[string]string{}
	rows, err := s.db.Query(ctx, `SELECT day, name FROM holidays WHERE day BETWEEN $1 AND $2`, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var d time.Time
		var name string
		if err := rows.Scan(&d, &name); err != nil {
			return nil, err
		}
		out[d.Format(dayLayout)] = name
	}
	return out, rows.Err()
}

// leaveDayValue is how much of a leave day d counts: 0 on Sundays/holidays, 0.5 for a half day.
func leaveDayValue(l *calLeave, off bool) float64 {
	switch {
	case off:
		return 0
	case l.Half != nil:
		return 0.5
	default:
		return 1
	}
}

func isOffDay(d time.Time, holidays map[string]string) bool {
	_, holiday := holidays[d.Format(dayLayout)]
	return d.Weekday() == time.Sunday || holiday
}

// buildDays colours each day from..to for one user. today is the current company-local date.
func (cd *calendarData) buildDays(u calUser, from, to, today time.Time) ([]calDay, calTotals) {
	totals := calTotals{LeaveByKind: map[string]float64{}}
	days := []calDay{}
	for d := from; !d.After(to); d = d.AddDate(0, 0, 1) {
		key := d.Format(dayLayout)
		c := calDay{Day: key, OffDay: isOffDay(d, cd.holidays), Holiday: cd.holidays[key]}
		for i := range cd.leaves[u.ID] {
			l := &cd.leaves[u.ID][i]
			if d.Before(l.start) || d.After(l.end) {
				continue
			}
			v := l.calLeave
			if l.Status == "APPROVED" {
				c.Leave = &v
			} else if c.PendingLeave == nil {
				c.PendingLeave = &v
			}
		}
		if c.OffDay { // a week of leave doesn't turn its Sunday into a leave day
			c.Leave, c.PendingLeave = nil, nil
		}
		w := cd.work[u.ID][key]
		worked := w.any()
		if w != nil {
			c.FirstIn, c.LastOut, c.WorkedMs, c.Overnight, c.ClockSkew = w.firstIn, w.lastOut, w.worked, w.overnight, w.skew
		}
		past := d.Before(today)
		tracked := u.tracked(d)

		work := "" // what the work part of the day was, if anything
		switch {
		case worked && w.open && past:
			work = "MISSED_CHECKOUT"
		case worked && w.open:
			work = "TODAY"
		case worked:
			work = "WORKED"
		}

		switch {
		case c.Leave != nil && c.Leave.Half == nil && worked:
			c.Status = "WORKED_ON_LEAVE"
		case c.Leave != nil && c.Leave.Half == nil:
			c.Status = "LEAVE"
		case c.Leave != nil:
			c.Status = "HALF_LEAVE"
			switch {
			case work == "TODAY":
			case work != "":
				c.HalfWork = work
			case past && tracked:
				c.HalfWork = "MISSED"
			}
		case worked:
			c.Status = work
		case c.OffDay:
			c.Status = "OFF"
		case d.After(today):
			c.Status = "FUTURE"
		case !tracked:
			c.Status = "NONE"
		case d.Equal(today):
			c.Status = "TODAY"
		default:
			c.Status = "MISSED"
		}

		switch cd.overrides[u.ID][key] {
		case "GRANT":
			c.CompOff = "GRANTED"
		case "REVOKE":
			if c.OffDay && worked {
				c.CompOff = "REVOKED"
			}
		default:
			if c.OffDay && worked {
				c.CompOff = "EARNED"
				switch {
				case w.skew:
					c.CompOffCheck = "CLOCK"
				case time.Duration(w.worked)*time.Millisecond < shortCompOff && !(w.open && !past):
					c.CompOffCheck = "SHORT"
				}
			}
		}

		if worked {
			totals.DaysWorked++
			totals.WorkedMs += c.WorkedMs
		}
		if !c.OffDay && !d.After(today) && tracked {
			totals.WorkingDays++
		}
		switch {
		case c.Status == "MISSED" || c.HalfWork == "MISSED":
			totals.Missed++
		case c.Status == "MISSED_CHECKOUT" || c.HalfWork == "MISSED_CHECKOUT":
			totals.MissedCheckouts++
		}
		switch {
		case c.Status == "WORKED_ON_LEAVE":
			totals.WorkedOnLeave++
		case c.Leave != nil:
			v := leaveDayValue(c.Leave, c.OffDay)
			totals.LeaveDays += v
			totals.LeaveByKind[c.Leave.Kind] += v
		}
		if c.CompOff == "EARNED" || c.CompOff == "GRANTED" {
			totals.CompOffsEarned++
		}
		days = append(days, c)
	}
	return days, totals
}

// mark is the one-word picture of a day for the team strip.
func (c calDay) mark() string {
	switch {
	case c.Status == "LEAVE" && c.Leave.Kind == "COMP_OFF":
		return "COMP_OFF"
	case c.Status == "HALF_LEAVE" && c.HalfWork != "":
		return "HALF_LEAVE_" + c.HalfWork
	}
	return c.Status
}

// ---- Comp-off balance --------------------------------------------------------------------------

type compOffBalance struct {
	Earned  int     `json:"earned"`  // all time: work on Sundays/holidays + admin grants − admin revokes
	Used    float64 `json:"used"`    // approved COMP_OFF leave (half day = 0.5)
	Pending float64 `json:"pending"` // COMP_OFF requests waiting for the admin
	Balance float64 `json:"balance"` // earned − used; can go negative (no rules yet, the admin decides)
}

func (s *Server) compOffBalance(ctx context.Context, userID string) (compOffBalance, error) {
	var b compOffBalance
	evs, err := s.loadEvents(ctx, []string{userID}, 0, 1<<62)
	if err != nil {
		return b, err
	}
	holidays, err := s.holidaysBetween(ctx, time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC), time.Date(2200, 1, 1, 0, 0, 0, 0, time.UTC))
	if err != nil {
		return b, err
	}
	earned := map[string]bool{}
	for day, w := range workByDay(evs[userID]) {
		if d, ok := parseDay(day); ok && w.any() && isOffDay(d, holidays) {
			earned[day] = true
		}
	}
	rows, err := s.db.Query(ctx, `SELECT day, action FROM comp_off_overrides WHERE user_id = $1`, userID)
	if err != nil {
		return b, err
	}
	for rows.Next() {
		var d time.Time
		var action string
		if err := rows.Scan(&d, &action); err != nil {
			rows.Close()
			return b, err
		}
		earned[d.Format(dayLayout)] = action == "GRANT"
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return b, err
	}
	for _, ok := range earned {
		if ok {
			b.Earned++
		}
	}
	rows, err = s.db.Query(ctx, `SELECT start_day, end_day, half, status FROM leave_requests
		WHERE user_id = $1 AND kind = 'COMP_OFF' AND status IN ('APPROVED', 'PENDING')`, userID)
	if err != nil {
		return b, err
	}
	defer rows.Close()
	for rows.Next() {
		var start, end time.Time
		var half *string
		var status string
		if err := rows.Scan(&start, &end, &half, &status); err != nil {
			return b, err
		}
		n := leaveDays(start, end, half, holidays)
		if status == "APPROVED" {
			b.Used += n
		} else {
			b.Pending += n
		}
	}
	b.Balance = float64(b.Earned) - b.Used
	return b, rows.Err()
}

// leaveDays counts the working days a leave covers (Sundays and holidays are free).
func leaveDays(start, end time.Time, half *string, holidays map[string]string) float64 {
	n := 0.0
	l := &calLeave{Half: half}
	for d := start; !d.After(end); d = d.AddDate(0, 0, 1) {
		n += leaveDayValue(l, isOffDay(d, holidays))
	}
	return n
}

// ---- Handlers --------------------------------------------------------------------------------

func (s *Server) today() time.Time {
	d, _ := parseDay(s.now().In(companyZone).Format(dayLayout))
	return d
}

func parseMonth(m string) (from, to time.Time, ok bool) {
	t, err := time.Parse(monthLayout, m)
	if err != nil || t.Year() < 2020 || t.Year() > 2100 {
		return time.Time{}, time.Time{}, false
	}
	return t, t.AddDate(0, 1, -1), true
}

// usersForCalendar: one user (any status) or, with id "", the team for the month from..to:
// tracked users who had started by its end and hadn't left before its start.
func (s *Server) usersForCalendar(ctx context.Context, id string, from, to time.Time) ([]calUser, error) {
	rows, err := s.db.Query(ctx, `SELECT u.id::text, u.employee_code, u.display_name, u.role, u.status, u.track_attendance, u.attendance_from::text,
		       CASE WHEN u.status <> 'ACTIVE' THEN (u.disabled_at AT TIME ZONE 'Asia/Kolkata')::date::text END
		FROM users u
		WHERE ($1 <> '' AND u.id::text = $1)
		   OR ($1 = '' AND u.track_attendance AND u.attendance_from <= $3
		       AND (u.status = 'ACTIVE' OR (u.disabled_at AT TIME ZONE 'Asia/Kolkata')::date >= $2))
		ORDER BY u.display_name`, id, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []calUser
	for rows.Next() {
		var u calUser
		if err := rows.Scan(&u.ID, &u.EmployeeCode, &u.DisplayName, &u.Role, &u.Status, &u.TrackAttendance, &u.AttendanceFrom, &u.LeftOn); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// GET /v1/calendar?month=YYYY-MM — the caller's own month.
func (s *Server) myCalendar(w http.ResponseWriter, r *http.Request) {
	s.calendarFor(w, r, userFrom(r).ID)
}

// GET /v1/admin/calendar?month=YYYY-MM&user=<id> — one engineer's month.
func (s *Server) adminCalendar(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("user")
	if !isUUID(id) {
		writeError(w, http.StatusBadRequest, "INVALID", "user must be a UUID")
		return
	}
	s.calendarFor(w, r, id)
}

func (s *Server) calendarFor(w http.ResponseWriter, r *http.Request, userID string) {
	from, to, ok := parseMonth(r.URL.Query().Get("month"))
	if !ok {
		writeError(w, http.StatusBadRequest, "INVALID", "month must be YYYY-MM")
		return
	}
	users, err := s.usersForCalendar(r.Context(), userID, from, to)
	if err != nil {
		s.internal(w, "calendar", err)
		return
	}
	if len(users) == 0 {
		writeError(w, http.StatusNotFound, "NOT_FOUND", "no such user")
		return
	}
	cd, err := s.loadCalendar(r.Context(), []string{userID}, from, to)
	if err != nil {
		s.internal(w, "calendar", err)
		return
	}
	bal, err := s.compOffBalance(r.Context(), userID)
	if err != nil {
		s.internal(w, "calendar", err)
		return
	}
	days, totals := cd.buildDays(users[0], from, to, s.today())
	writeJSON(w, http.StatusOK, map[string]any{
		"user": users[0], "month": from.Format(monthLayout), "today": s.today().Format(dayLayout),
		"days": days, "totals": totals, "comp_off": bal,
	})
}

type calTeamRow struct {
	User    calUser   `json:"user"`
	Days    []string  `json:"days"`    // one mark per day of the month (see calDay.mark)
	Pending []string  `json:"pending"` // days with a leave request waiting for the admin
	Totals  calTotals `json:"totals"`
}

// A comp-off earned or granted this month, listed so the admin notices new ones.
type calCompOff struct {
	UserID      string `json:"user_id"`
	DisplayName string `json:"display_name"`
	Day         string `json:"day"`
	Kind        string `json:"kind"`  // EARNED | GRANTED
	Check       string `json:"check"` // "" | SHORT | CLOCK (see calDay.CompOffCheck)
	WorkedMs    int64  `json:"worked_ms"`
}

// GET /v1/admin/calendar/team?month=YYYY-MM — everyone × every day, marks only.
func (s *Server) teamCalendar(w http.ResponseWriter, r *http.Request) {
	from, to, ok := parseMonth(r.URL.Query().Get("month"))
	if !ok {
		writeError(w, http.StatusBadRequest, "INVALID", "month must be YYYY-MM")
		return
	}
	users, err := s.usersForCalendar(r.Context(), "", from, to)
	if err != nil {
		s.internal(w, "team calendar", err)
		return
	}
	ids := make([]string, len(users))
	for i, u := range users {
		ids[i] = u.ID
	}
	cd, err := s.loadCalendar(r.Context(), ids, from, to)
	if err != nil {
		s.internal(w, "team calendar", err)
		return
	}
	today := s.today()
	out := []calTeamRow{}
	compOffs := []calCompOff{}
	for _, u := range users {
		days, totals := cd.buildDays(u, from, to, today)
		row := calTeamRow{User: u, Totals: totals, Days: []string{}, Pending: []string{}}
		for _, d := range days {
			row.Days = append(row.Days, d.mark())
			if d.PendingLeave != nil {
				row.Pending = append(row.Pending, d.Day)
			}
			if d.CompOff == "EARNED" || d.CompOff == "GRANTED" {
				compOffs = append(compOffs, calCompOff{UserID: u.ID, DisplayName: u.DisplayName, Day: d.Day, Kind: d.CompOff,
					Check: d.CompOffCheck, WorkedMs: d.WorkedMs})
			}
		}
		out = append(out, row)
	}
	slices.SortStableFunc(compOffs, func(a, b calCompOff) int {
		if a.Day > b.Day {
			return -1
		}
		if a.Day < b.Day {
			return 1
		}
		return 0
	})
	holidays := []map[string]string{}
	for day, name := range cd.holidays {
		holidays = append(holidays, map[string]string{"day": day, "name": name})
	}
	slices.SortFunc(holidays, func(a, b map[string]string) int {
		if a["day"] < b["day"] {
			return -1
		}
		return 1
	})
	writeJSON(w, http.StatusOK, map[string]any{"month": from.Format(monthLayout), "today": today.Format(dayLayout),
		"users": out, "holidays": holidays, "comp_offs": compOffs})
}
