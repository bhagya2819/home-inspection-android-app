package api

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/xml"
	"io"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// ist returns epoch ms for a wall-clock time in India on a day of October 2026.
func ist(day, hour, min int) int64 {
	return time.Date(2026, 10, day, hour, min, 0, 0, companyZone).UnixMilli()
}

func checkIO(h *harness, tok, kind string, at int64) {
	h.t.Helper()
	selfie := []byte("\xff\xd8 selfie \xff\xd9")
	if st := h.do("POST", "/v1/attendance", tok, map[string]any{
		"id": newUUID(), "kind": kind, "client_at": at, "time_zone": "Asia/Kolkata",
		"face_result": "MATCH", "liveness_passed": true, "selfie_sha256": sha(selfie), "selfie_size": len(selfie),
	}, nil); st != 200 {
		h.t.Fatalf("attendance %s: %d", kind, st)
	}
}

type calendarResp struct {
	Days    []calDay       `json:"days"`
	Totals  calTotals      `json:"totals"`
	CompOff compOffBalance `json:"comp_off"`
}

func leave(kind, start, end string, half any) map[string]any {
	return map[string]any{"id": newUUID(), "kind": kind, "start_day": start, "end_day": end, "half": half, "reason": "family"}
}

func TestLeaveAndCalendar(t *testing.T) {
	h := setup(t)
	h.user("deva", "ADMIN")
	h.user("insp", "INSPECTOR")
	h.user("other", "INSPECTOR")
	admin, insp, other := h.login("deva"), h.login("insp"), h.login("other")
	inspID := userID(h, "insp")
	// Today is Thursday 22 Oct 2026, 10:00 in India; the engineers are tracked since September.
	h.server.now = func() time.Time { return time.Date(2026, 10, 22, 10, 0, 0, 0, companyZone) }
	if _, err := h.db.Exec(context.Background(), `UPDATE users SET attendance_from = '2026-09-01'`); err != nil {
		t.Fatal(err)
	}

	if st := h.do("PUT", "/v1/admin/holidays/2026-10-02", insp, map[string]string{"name": "Gandhi Jayanti"}, nil); st != 403 {
		t.Fatalf("inspector set a holiday: %d", st)
	}
	if st := h.do("PUT", "/v1/admin/holidays/2026-10-02", admin, map[string]string{"name": "Gandhi Jayanti"}, nil); st != 200 {
		t.Fatalf("holiday: %d", st)
	}

	checkIO(h, insp, "IN", ist(5, 10, 0)) // Mon: a full day
	checkIO(h, insp, "OUT", ist(5, 18, 0))
	checkIO(h, insp, "IN", ist(6, 9, 30))  // Tue: never checked out
	checkIO(h, insp, "IN", ist(11, 11, 0)) // Sunday: earns a comp-off
	checkIO(h, insp, "OUT", ist(11, 13, 0))
	checkIO(h, insp, "IN", ist(14, 9, 0)) // Wed: two sessions and a stray second IN
	checkIO(h, insp, "IN", ist(14, 9, 5))
	checkIO(h, insp, "OUT", ist(14, 12, 0))
	checkIO(h, insp, "IN", ist(14, 13, 0))
	checkIO(h, insp, "OUT", ist(14, 17, 0))

	// Engineer requests.
	casual := leave("CASUAL", "2026-10-08", "2026-10-09", nil)
	var got leaveOut
	if st := h.do("POST", "/v1/leave", insp, casual, &got); st != 200 || got.Status != "PENDING" || got.Days != 2 {
		t.Fatalf("leave request: %d %+v", st, got)
	}
	if st := h.do("POST", "/v1/leave", insp, casual, nil); st != 200 {
		t.Fatalf("retry: %d", st)
	}
	if st := h.do("POST", "/v1/leave", other, casual, nil); st != 409 {
		t.Fatalf("someone else's id: %d", st)
	}
	if st := h.do("POST", "/v1/leave", other, map[string]any{"id": newUUID(), "user_id": inspID, "kind": "CASUAL",
		"start_day": "2026-10-28", "end_day": "2026-10-28"}, nil); st != 403 {
		t.Fatalf("filed for another user: %d", st)
	}
	if st := h.do("POST", "/v1/leave", insp, leave("CASUAL", "2026-10-09", "2026-10-10", nil), nil); st != 409 {
		t.Fatalf("overlap accepted: %d", st)
	}
	for name, body := range map[string]map[string]any{
		"bad kind":       leave("HOLIDAY", "2026-10-28", "2026-10-28", nil),
		"reversed":       leave("CASUAL", "2026-10-28", "2026-10-27", nil),
		"half of two":    leave("CASUAL", "2026-10-27", "2026-10-28", "FIRST"),
		"bad half":       leave("CASUAL", "2026-10-28", "2026-10-28", "THIRD"),
		"long ago":       leave("SICK", "2026-07-01", "2026-07-01", nil),
		"too long":       leave("CASUAL", "2026-11-01", "2027-02-01", nil),
		"not a date":     leave("CASUAL", "28-10-2026", "2026-10-28", nil),
		"only a Sunday":  leave("CASUAL", "2026-10-25", "2026-10-25", nil),
		"only a holiday": leave("CASUAL", "2026-10-02", "2026-10-02", nil),
	} {
		if st := h.do("POST", "/v1/leave", insp, body, nil); st != 400 {
			t.Fatalf("%s: %d", name, st)
		}
	}
	sick := leave("SICK", "2026-10-12", "2026-10-12", "SECOND")
	h.do("POST", "/v1/leave", insp, sick, nil)
	comp := leave("COMP_OFF", "2026-10-13", "2026-10-13", nil)
	h.do("POST", "/v1/leave", insp, comp, nil)
	pending := leave("CASUAL", "2026-10-17", "2026-10-19", nil) // Sat–Mon: the Sunday doesn't count
	h.do("POST", "/v1/leave", insp, pending, &got)
	if got.Days != 2 {
		t.Fatalf("Sunday counted as leave: %v", got.Days)
	}
	future := leave("CASUAL", "2026-10-30", "2026-10-30", nil)
	h.do("POST", "/v1/leave", insp, future, nil)

	// Admin decides.
	if st := h.do("POST", "/v1/admin/leave/"+casual["id"].(string)+"/decision", insp, map[string]any{"approve": true}, nil); st != 403 {
		t.Fatalf("inspector approved: %d", st)
	}
	var list struct {
		Requests []leaveOut `json:"requests"`
		Pending  int        `json:"pending"`
	}
	h.do("GET", "/v1/admin/leave?status=PENDING", admin, nil, &list)
	if list.Pending != 5 || len(list.Requests) != 5 {
		t.Fatalf("pending list: %+v", list)
	}
	for _, l := range []map[string]any{casual, sick, comp, future} {
		if st := h.do("POST", "/v1/admin/leave/"+l["id"].(string)+"/decision", admin, map[string]any{"approve": true, "note": "ok"}, &got); st != 200 || got.Status != "APPROVED" {
			t.Fatalf("approve: %d %+v", st, got)
		}
	}
	if st := h.do("POST", "/v1/admin/leave/"+casual["id"].(string)+"/decision", admin, map[string]any{"approve": true}, nil); st != 409 {
		t.Fatalf("approved twice: %d", st)
	}

	// Engineer cancels: approved future leave yes, approved past leave no.
	if st := h.do("POST", "/v1/leave/"+casual["id"].(string)+"/cancel", insp, nil, nil); st != 409 {
		t.Fatalf("cancelled past leave: %d", st)
	}
	if st := h.do("POST", "/v1/leave/"+future["id"].(string)+"/cancel", other, nil, nil); st != 404 {
		t.Fatalf("cancelled someone else's: %d", st)
	}
	if st := h.do("POST", "/v1/leave/"+future["id"].(string)+"/cancel", insp, nil, &got); st != 200 || got.Status != "CANCELLED" {
		t.Fatalf("cancel: %d %+v", st, got)
	}

	// Admin records leave directly (approved at once).
	direct := leave("SICK", "2026-10-20", "2026-10-20", nil)
	direct["user_id"] = inspID
	if st := h.do("POST", "/v1/admin/leave", admin, direct, &got); st != 200 || got.Status != "APPROVED" || !got.ByAdmin {
		t.Fatalf("admin record: %d %+v", st, got)
	}

	var cal calendarResp
	if st := h.do("GET", "/v1/admin/calendar?month=2026-10&user="+inspID, admin, nil, &cal); st != 200 || len(cal.Days) != 31 {
		t.Fatalf("calendar: %d %d", st, len(cal.Days))
	}
	want := map[int]string{
		1: "MISSED", 2: "OFF", 3: "MISSED", 4: "OFF", 5: "WORKED", 6: "MISSED_CHECKOUT", 7: "MISSED", 8: "LEAVE", 9: "LEAVE",
		11: "WORKED", 12: "HALF_LEAVE", 13: "LEAVE", 14: "WORKED", 17: "MISSED", 18: "OFF", 20: "LEAVE",
		22: "TODAY", 23: "FUTURE", 30: "FUTURE",
	}
	for day, st := range want {
		if got := cal.Days[day-1].Status; got != st {
			t.Errorf("Oct %d: %s, want %s", day, got, st)
		}
	}
	d := cal.Days
	if d[1].Holiday != "Gandhi Jayanti" || !d[1].OffDay {
		t.Errorf("holiday: %+v", d[1])
	}
	if d[4].WorkedMs != 8*3600_000 || *d[4].FirstIn != ist(5, 10, 0) || *d[4].LastOut != ist(5, 18, 0) {
		t.Errorf("Oct 5 hours: %+v", d[4])
	}
	if d[13].WorkedMs != 7*3600_000 { // 9:00–12:00 + 13:00–17:00; the 9:05 IN is ignored
		t.Errorf("Oct 14 hours: %d", d[13].WorkedMs)
	}
	if d[10].CompOff != "EARNED" || d[10].CompOffCheck != "" || d[12].Leave.Kind != "COMP_OFF" || *d[11].Leave.Half != "SECOND" || d[11].HalfWork != "MISSED" {
		t.Errorf("comp-off/half: %+v %+v %+v", d[10], d[12], d[11])
	}
	if d[16].PendingLeave == nil || d[17].PendingLeave != nil {
		t.Errorf("pending leave: %+v / Sunday %+v", d[16], d[17])
	}
	if d[29].Leave != nil {
		t.Errorf("cancelled leave still shown: %+v", d[29])
	}
	tot := cal.Totals
	// Missed: 9 days without a check-in + the other half of the 12th's half-day leave.
	if tot.DaysWorked != 4 || tot.Missed != 10 || tot.MissedCheckouts != 1 || tot.LeaveDays != 4.5 ||
		tot.LeaveByKind["CASUAL"] != 2 || tot.LeaveByKind["SICK"] != 1.5 || tot.CompOffsEarned != 1 {
		t.Errorf("totals: %+v", tot)
	}
	if cal.CompOff.Earned != 1 || cal.CompOff.Used != 1 || cal.CompOff.Balance != 0 {
		t.Errorf("comp-off balance: %+v", cal.CompOff)
	}

	// Admin grants a comp-off for Sunday the 4th and takes back the automatic one of the 11th.
	if st := h.do("POST", "/v1/admin/comp-offs", admin, map[string]any{"user_id": inspID, "day": "2026-10-04", "action": "GRANT"}, nil); st != 200 {
		t.Fatalf("grant: %d", st)
	}
	var bal struct {
		CompOff compOffBalance `json:"comp_off"`
	}
	h.do("POST", "/v1/admin/comp-offs", admin, map[string]any{"user_id": inspID, "day": "2026-10-11", "action": "REVOKE"}, &bal)
	if bal.CompOff.Earned != 1 {
		t.Fatalf("after grant+revoke: %+v", bal.CompOff)
	}
	h.do("POST", "/v1/admin/comp-offs", admin, map[string]any{"user_id": inspID, "day": "2026-10-11", "action": "CLEAR"}, &bal)
	if bal.CompOff.Earned != 2 || bal.CompOff.Balance != 1 {
		t.Fatalf("after clear: %+v", bal.CompOff)
	}

	// The engineer sees their own month and requests, never someone else's.
	if st := h.do("GET", "/v1/calendar?month=2026-10", insp, nil, &cal); st != 200 || cal.Days[4].Status != "WORKED" {
		t.Fatalf("own calendar: %d", st)
	}
	if st := h.do("GET", "/v1/admin/calendar?month=2026-10&user="+inspID, insp, nil, nil); st != 403 {
		t.Fatalf("inspector read admin calendar: %d", st)
	}
	var mine struct {
		Requests []leaveOut     `json:"requests"`
		CompOff  compOffBalance `json:"comp_off"`
	}
	h.do("GET", "/v1/leave", other, nil, &mine)
	if len(mine.Requests) != 0 {
		t.Fatalf("other sees requests: %+v", mine.Requests)
	}
	h.do("GET", "/v1/leave", insp, nil, &mine)
	if len(mine.Requests) != 6 || mine.Requests[0].Status != "PENDING" {
		t.Fatalf("own requests: %+v", mine.Requests)
	}

	var team struct {
		Users []calTeamRow `json:"users"`
	}
	if st := h.do("GET", "/v1/admin/calendar/team?month=2026-10", admin, nil, &team); st != 200 || len(team.Users) != 2 {
		t.Fatalf("team: %d %d", st, len(team.Users)) // admins aren't tracked unless switched on: not listed
	}
	for _, row := range team.Users {
		if row.User.EmployeeCode == "INSP" && (row.Days[12] != "COMP_OFF" || row.Days[4] != "WORKED" || row.Days[11] != "HALF_LEAVE_MISSED" ||
			len(row.Pending) != 2 || row.Pending[0] != "2026-10-17") {
			t.Fatalf("team row: %v", row.Days)
		}
	}

	// Monthly export: admins only (phone or web), an .xlsx whose sheets are well-formed XML.
	if resp, _ := h.get("/v1/admin/calendar/export?month=2026-10", insp, nil); resp.StatusCode != 403 {
		t.Fatalf("inspector export: %d", resp.StatusCode)
	}
	if !webRouteAllowed(httptest.NewRequest("GET", "/v1/admin/calendar/export?month=2026-10", nil)) {
		t.Fatal("web dashboard can't download the export")
	}
	resp, body := h.get("/v1/admin/calendar/export?month=2026-10", admin, nil)
	if resp.StatusCode != 200 || !strings.Contains(resp.Header.Get("Content-Disposition"), "HomeInspection-Attendance-2026-10.xlsx") {
		t.Fatalf("export: %d %s", resp.StatusCode, body)
	}
	zr, err := zip.NewReader(bytes.NewReader(body), int64(len(body)))
	if err != nil {
		t.Fatal(err)
	}
	sheets := map[string]string{}
	for _, f := range zr.File {
		rc, _ := f.Open()
		raw, _ := io.ReadAll(rc)
		rc.Close()
		if strings.HasSuffix(f.Name, ".xml") || strings.HasSuffix(f.Name, ".rels") {
			for d := xml.NewDecoder(bytes.NewReader(raw)); ; {
				if _, err := d.Token(); err == io.EOF {
					break
				} else if err != nil {
					t.Fatalf("%s: %v", f.Name, err)
				}
			}
		}
		sheets[f.Name] = string(raw)
	}
	summary, daily := sheets["xl/worksheets/sheet1.xml"], sheets["xl/worksheets/sheet2.xml"]
	// INSP: Mon 5 Oct 10:00-18:00 = 8 h, Wed 14 Oct 3 h + 4 h = 7 h, Sun 11 Oct 2 h, plus the open Tue 6 Oct.
	for _, want := range []string{">INSP<", ">Comp-off balance (today)<"} {
		if !strings.Contains(summary, want) {
			t.Fatalf("summary lacks %q", want)
		}
	}
	for _, want := range []string{">2026-10-05<", ">10:00<", ">18:00<", "<v>8</v>", ">Missed check-out<", ">2026-10-11<", ">Earned<", ">Gandhi Jayanti<"} {
		if !strings.Contains(daily, want) {
			t.Fatalf("day by day lacks %q", want)
		}
	}
	if strings.Contains(daily, ">2026-10-23<") {
		t.Fatal("future days exported")
	}
}

// ---- Day rules (pure: no database) ----------------------------------------------------------------

func nov(day, hour, min int) int64 {
	return time.Date(2026, 11, day, hour, min, 0, 0, companyZone).UnixMilli()
}

func TestWorkByDay(t *testing.T) {
	// Saturday 7 Nov 23:30 → Sunday 8 Nov 00:40: 30 minutes on Saturday, 40 on Sunday.
	w := workByDay([]event{{"IN", nov(7, 23, 30), false}, {"OUT", nov(8, 0, 40), false}})
	sat, sun := w["2026-11-07"], w["2026-11-08"]
	if sat.worked != 30*60_000 || sat.open || *sat.firstIn != nov(7, 23, 30) || sat.lastOut != nil {
		t.Errorf("Saturday: %+v", sat)
	}
	if sun.worked != 40*60_000 || !sun.overnight || sun.firstIn != nil || *sun.lastOut != nov(8, 0, 40) || !sun.any() {
		t.Errorf("Sunday: %+v", sun)
	}

	// An IN never closed, then a new day's shift: the old one stays open, the new one counts.
	w = workByDay([]event{{"IN", nov(2, 9, 0), false}, {"IN", nov(3, 9, 0), false}, {"OUT", nov(3, 18, 0), false}})
	if !w["2026-11-02"].open || w["2026-11-02"].worked != 0 || w["2026-11-03"].worked != 9*3600_000 || w["2026-11-03"].open {
		t.Errorf("stale IN: %+v %+v", w["2026-11-02"], w["2026-11-03"])
	}

	// An OUT more than 16 h after the IN doesn't close it.
	w = workByDay([]event{{"IN", nov(2, 8, 0), false}, {"OUT", nov(3, 9, 0), false}})
	if !w["2026-11-02"].open || w["2026-11-02"].worked != 0 || w["2026-11-03"].any() {
		t.Errorf("too long: %+v %+v", w["2026-11-02"], w["2026-11-03"])
	}

	// A skewed clock marks the day.
	w = workByDay([]event{{"IN", nov(4, 9, 0), true}, {"OUT", nov(4, 10, 0), false}})
	if !w["2026-11-04"].skew {
		t.Errorf("skew: %+v", w["2026-11-04"])
	}
}

func TestBuildDays(t *testing.T) {
	half := "FIRST"
	user := calUser{ID: "u", TrackAttendance: true, AttendanceFrom: "2026-11-01"}
	day := func(s string) time.Time { d, _ := parseDay(s); return d }
	cd := &calendarData{
		work: map[string]map[string]*dayWork{"u": workByDay([]event{
			{"IN", nov(7, 23, 30), false}, {"OUT", nov(8, 0, 40), false}, // Sat night into Sunday: a short comp-off
			{"IN", nov(10, 9, 0), false}, {"OUT", nov(10, 17, 0), false}, // worked on a leave day
			{"IN", nov(11, 14, 0), false}, {"OUT", nov(11, 18, 0), false}, // half-day leave + afternoon at work
			{"IN", nov(13, 9, 0), false},                                 // half-day leave, checked in, never out
			{"IN", nov(14, 10, 0), true}, {"OUT", nov(14, 15, 0), false}, // a holiday, phone clock off
		})},
		leaves: map[string][]leaveRow{"u": {
			{calLeave: calLeave{ID: "a", Kind: "CASUAL", Status: "APPROVED"}, userID: "u", start: day("2026-11-10"), end: day("2026-11-10")},
			{calLeave: calLeave{ID: "b", Kind: "SICK", Status: "APPROVED", Half: &half}, userID: "u", start: day("2026-11-11"), end: day("2026-11-11")},
			{calLeave: calLeave{ID: "c", Kind: "SICK", Status: "APPROVED", Half: &half}, userID: "u", start: day("2026-11-12"), end: day("2026-11-12")},
			{calLeave: calLeave{ID: "d", Kind: "SICK", Status: "APPROVED", Half: &half}, userID: "u", start: day("2026-11-13"), end: day("2026-11-13")},
			{calLeave: calLeave{ID: "e", Kind: "CASUAL", Status: "APPROVED"}, userID: "u", start: day("2026-11-16"), end: day("2026-11-17")}, // over a later holiday
		}},
		holidays:  map[string]string{"2026-11-14": "Diwali", "2026-11-16": "Added later"},
		overrides: map[string]map[string]string{},
	}
	days, tot := cd.buildDays(user, day("2026-11-01"), day("2026-11-30"), day("2026-11-18"))
	get := func(n int) calDay { return days[n-1] }
	if d := get(8); d.Status != "WORKED" || d.CompOff != "EARNED" || d.CompOffCheck != "SHORT" || !d.Overnight {
		t.Errorf("Sunday tail of a night shift: %+v", d)
	}
	if d := get(10); d.Status != "WORKED_ON_LEAVE" || d.mark() != "WORKED_ON_LEAVE" {
		t.Errorf("worked on leave: %+v", d)
	}
	if d := get(11); d.Status != "HALF_LEAVE" || d.HalfWork != "WORKED" || d.mark() != "HALF_LEAVE_WORKED" {
		t.Errorf("half + work: %+v", d)
	}
	if d := get(12); d.HalfWork != "MISSED" {
		t.Errorf("half, other half missed: %+v", d)
	}
	if d := get(13); d.HalfWork != "MISSED_CHECKOUT" {
		t.Errorf("half, no check-out: %+v", d)
	}
	if d := get(14); d.Status != "WORKED" || d.CompOff != "EARNED" || d.CompOffCheck != "CLOCK" || !d.ClockSkew {
		t.Errorf("holiday with skewed clock: %+v", d)
	}
	if d := get(16); d.Leave != nil || d.Status != "OFF" {
		t.Errorf("leave over a holiday added later: %+v", d)
	}
	if d := get(17); d.Status != "LEAVE" {
		t.Errorf("leave day after the holiday: %+v", d)
	}
	if d := get(19); d.Status != "FUTURE" {
		t.Errorf("future: %+v", d)
	}
	// Worked: 7, 8, 10, 11, 13, 14. Leave: 11, 12, 13 half (1.5) + 17 (1); the 10th counts as worked.
	// Missed: 2–6, 9 (6 days) + the 12th's other half; missed check-out: the 13th's other half.
	if tot.DaysWorked != 6 || tot.WorkedOnLeave != 1 || tot.LeaveDays != 2.5 || tot.Missed != 7 || tot.MissedCheckouts != 1 || tot.CompOffsEarned != 2 {
		t.Errorf("totals: %+v", tot)
	}

	// Tracking: nothing counts before attendance_from or after leaving, nor for an untracked account.
	left := "2026-11-05"
	days, tot = cd.buildDays(calUser{ID: "x", TrackAttendance: true, AttendanceFrom: "2026-11-03", LeftOn: &left}, day("2026-11-01"), day("2026-11-30"), day("2026-11-18"))
	if days[1].Status != "NONE" || days[2].Status != "MISSED" || days[5].Status != "NONE" || tot.Missed != 3 || tot.WorkingDays != 3 {
		t.Errorf("joined/left: %s %s %s %+v", days[1].Status, days[2].Status, days[5].Status, tot)
	}
	days, tot = cd.buildDays(calUser{ID: "u", AttendanceFrom: "2026-11-01"}, day("2026-11-01"), day("2026-11-30"), day("2026-11-18"))
	if days[1].Status != "NONE" || days[9].Status != "WORKED_ON_LEAVE" || tot.Missed != 0 || tot.DaysWorked != 6 {
		t.Errorf("untracked: %s %s %+v", days[1].Status, days[9].Status, tot)
	}
}

// ---- With the database ----------------------------------------------------------------------------

func TestCalendarUsesServerClockAndTeamRules(t *testing.T) {
	h := setup(t)
	h.user("deva", "ADMIN")
	h.user("insp", "INSPECTOR")
	h.user("gone", "INSPECTOR")
	h.user("late", "INSPECTOR")
	h.user("off", "INSPECTOR")
	admin, insp := h.login("deva"), h.login("insp")
	inspID := userID(h, "insp")
	h.server.now = func() time.Time { return time.Date(2026, 10, 22, 10, 0, 0, 0, companyZone) }
	ctx := context.Background()
	h.db.Exec(ctx, `UPDATE users SET attendance_from = '2026-10-01'`)
	h.db.Exec(ctx, `UPDATE users SET attendance_from = '2026-11-02' WHERE employee_code = 'LATE'`)
	h.db.Exec(ctx, `UPDATE users SET status = 'DISABLED', disabled_at = '2026-10-10T06:00:00Z' WHERE employee_code = 'GONE'`)
	if st := h.do("PATCH", "/v1/admin/users/"+userID(h, "off"), admin, map[string]any{"track_attendance": false}, nil); st != 204 {
		t.Fatalf("track off: %d", st)
	}
	if st := h.do("PATCH", "/v1/admin/users/"+userID(h, "off"), admin, map[string]any{"attendance_from": "10/10/2026"}, nil); st != 400 {
		t.Fatalf("bad joining date: %d", st)
	}

	// The phone's clock says Sunday 18 Oct (a comp-off!), but its monotonic clock says the check-in
	// was an hour ago: the server's estimate (today, 09:00) wins.
	selfie := []byte("\xff\xd8 selfie \xff\xd9")
	h.do("POST", "/v1/attendance", insp, map[string]any{
		"id": newUUID(), "kind": "IN", "client_at": ist(18, 9, 0), "time_zone": "Asia/Kolkata",
		"elapsed_ms": 1_000_000, "boot_count": 3, "now_elapsed_ms": 1_000_000 + 3600_000, "now_boot_count": 3,
		"face_result": "MATCH", "liveness_passed": true, "selfie_sha256": sha(selfie), "selfie_size": len(selfie),
	}, nil)
	var cal calendarResp
	h.do("GET", "/v1/admin/calendar?month=2026-10&user="+inspID, admin, nil, &cal)
	if d := cal.Days[17]; d.Status != "OFF" || d.CompOff != "" {
		t.Errorf("Oct 18 by the phone's clock: %+v", d)
	}
	if d := cal.Days[21]; d.Status != "TODAY" || !d.ClockSkew || d.FirstIn == nil || *d.FirstIn != ist(22, 9, 0) {
		t.Errorf("Oct 22 by the server's estimate: %+v", d)
	}
	if cal.CompOff.Earned != 0 {
		t.Errorf("comp-off from a wrong clock: %+v", cal.CompOff)
	}

	// Team: the disabled engineer is still in October (they worked until the 10th), not in November;
	// the late joiner only from November; the untracked one and the admin never.
	codes := func(month string) []string {
		var team struct {
			Users []calTeamRow `json:"users"`
		}
		h.do("GET", "/v1/admin/calendar/team?month="+month, admin, nil, &team)
		var out []string
		for _, u := range team.Users {
			out = append(out, u.User.EmployeeCode)
		}
		return out
	}
	if got := codes("2026-10"); len(got) != 2 || got[0] != "GONE" || got[1] != "INSP" {
		t.Errorf("October team: %v", got)
	}
	if got := codes("2026-11"); len(got) != 2 || got[0] != "INSP" || got[1] != "LATE" {
		t.Errorf("November team: %v", got)
	}
	h.do("GET", "/v1/admin/calendar?month=2026-10&user="+userID(h, "gone"), admin, nil, &cal)
	// Tracked through the day they left (Sat 10 Oct), not after (Mon 12 Oct).
	if cal.Days[9].Status != "MISSED" || cal.Days[11].Status != "NONE" {
		t.Errorf("after leaving: %s %s", cal.Days[9].Status, cal.Days[11].Status)
	}
}

func TestLeaveDecisions(t *testing.T) {
	h := setup(t)
	h.user("deva", "ADMIN")
	h.user("insp", "INSPECTOR")
	admin, insp := h.login("deva"), h.login("insp")
	inspID := userID(h, "insp")
	h.server.now = func() time.Time { return time.Date(2026, 10, 22, 10, 0, 0, 0, companyZone) }
	h.db.Exec(context.Background(), `UPDATE users SET attendance_from = '2026-10-01'`)
	checkIO(h, insp, "IN", ist(20, 9, 0)) // worked on the day they later call sick
	checkIO(h, insp, "OUT", ist(20, 17, 0))

	var got leaveOut
	sick := leave("SICK", "2026-10-20", "2026-10-21", nil)
	h.do("POST", "/v1/leave", insp, sick, nil)
	comp := leave("COMP_OFF", "2026-10-27", "2026-10-27", nil)
	h.do("POST", "/v1/leave", insp, comp, nil)
	var list struct {
		Requests []leaveOut `json:"requests"`
	}
	h.do("GET", "/v1/admin/leave?status=PENDING", admin, nil, &list)
	for _, l := range list.Requests {
		switch l.Kind {
		case "SICK":
			if l.WorkedDays == nil || *l.WorkedDays != 1 || l.CompOff != nil {
				t.Errorf("sick annotations: %+v", l)
			}
		case "COMP_OFF":
			if l.CompOff == nil || l.CompOff.Balance != 0 || l.CompOff.Pending != 1 {
				t.Errorf("comp-off annotations: %+v", l)
			}
		}
	}

	// The admin's own record for the same days points to the pending request.
	direct := leave("SICK", "2026-10-21", "2026-10-21", nil)
	direct["user_id"] = inspID
	var e struct {
		Error struct{ Code, Message string }
	}
	if st := h.do("POST", "/v1/admin/leave", admin, direct, &e); st != 409 || !strings.Contains(e.Error.Message, "pending request") {
		t.Fatalf("admin record over pending: %d %+v", st, e)
	}

	// Approve → take back (REVOKED) → approve again; reject → approve after all.
	decide := func(id string, approve bool) int {
		return h.do("POST", "/v1/admin/leave/"+id+"/decision", admin, map[string]any{"approve": approve}, &got)
	}
	id := sick["id"].(string)
	if st := decide(id, true); st != 200 || got.Status != "APPROVED" {
		t.Fatalf("approve: %d %+v", st, got)
	}
	if st := decide(id, false); st != 200 || got.Status != "REVOKED" {
		t.Fatalf("take back: %d %+v", st, got)
	}
	if st := decide(id, true); st != 200 || got.Status != "APPROVED" {
		t.Fatalf("approve again: %d %+v", st, got)
	}
	cid := comp["id"].(string)
	if st := decide(cid, false); st != 200 || got.Status != "REJECTED" {
		t.Fatalf("reject: %d %+v", st, got)
	}
	// While rejected, the engineer asks again for the same day; approving the old one now overlaps.
	again := leave("CASUAL", "2026-10-27", "2026-10-27", nil)
	h.do("POST", "/v1/leave", insp, again, nil)
	if st := decide(cid, true); st != 409 {
		t.Fatalf("re-approve over a pending request: %d", st)
	}
	h.do("POST", "/v1/leave/"+again["id"].(string)+"/cancel", insp, nil, nil)
	if st := decide(cid, true); st != 200 || got.Status != "APPROVED" {
		t.Fatalf("approve after all: %d %+v", st, got)
	}
	// Cancelled is final.
	if st := decide(again["id"].(string), true); st != 409 {
		t.Fatalf("approved a cancelled request: %d", st)
	}

	// An admin may decide their own leave only while they are the only admin.
	own := leave("CASUAL", "2026-10-29", "2026-10-29", nil)
	h.do("POST", "/v1/leave", admin, own, nil)
	h.user("deva2", "ADMIN")
	if st := decide(own["id"].(string), true); st != 403 {
		t.Fatalf("self approval with another admin: %d", st)
	}
	if st := h.do("POST", "/v1/admin/comp-offs", admin, map[string]any{"user_id": userID(h, "deva"), "day": "2026-10-18", "action": "GRANT"}, nil); st != 403 {
		t.Fatalf("self comp-off with another admin: %d", st)
	}
	h.db.Exec(context.Background(), `UPDATE users SET status = 'DISABLED' WHERE employee_code = 'DEVA2'`)
	if st := decide(own["id"].(string), true); st != 200 {
		t.Fatalf("self approval as the only admin: %d", st)
	}

	// Holidays say whose records they change.
	var hol struct{ Affects int }
	if st := h.do("PUT", "/v1/admin/holidays/2026-10-20", admin, map[string]string{"name": "Dussehra"}, &hol); st != 200 || hol.Affects != 1 {
		t.Fatalf("holiday affects: %d %+v", st, hol)
	}
	if st := h.do("PUT", "/v1/admin/holidays/1999-10-20", admin, map[string]string{"name": "Old"}, nil); st != 400 {
		t.Fatalf("holiday in 1999: %d", st)
	}
	if st := h.do("DELETE", "/v1/admin/holidays/2026-10-20", admin, nil, &hol); st != 200 || hol.Affects != 1 {
		t.Fatalf("holiday removed: %d %+v", st, hol)
	}
}
