package api

import (
	"archive/zip"
	"bytes"
	"fmt"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Monthly attendance export (admin, web dashboard): one Excel file with a Summary sheet (one row per
// engineer, for payroll) and a Day by day sheet (one row per engineer per day). Built from the same
// buildDays as the calendar, so the file never disagrees with what the dashboard shows.
//
// Excel rather than CSV: hours stay numbers, the header row stays frozen, and text is always text
// (a CSV cell starting with "=" from a leave reason would run as a formula when opened).

var exportStatusLabel = map[string]string{
	"WORKED": "Worked", "MISSED_CHECKOUT": "Missed check-out", "MISSED": "Missed check-in", "LEAVE": "Leave",
	"WORKED_ON_LEAVE": "Worked on leave", "HALF_LEAVE": "Half-day leave", "OFF": "Sunday / holiday",
	"TODAY": "Today (in progress)", "NONE": "Not tracked",
}

var exportLeaveLabel = map[string]string{"CASUAL": "Casual", "SICK": "Sick", "COMP_OFF": "Comp-off"}

func leaveText(l *calLeave) string {
	if l == nil {
		return ""
	}
	s := exportLeaveLabel[l.Kind]
	if l.Half != nil {
		s += map[string]string{"FIRST": ", first half", "SECOND": ", second half"}[*l.Half]
	}
	return s
}

func clockText(ms *int64) string {
	if ms == nil {
		return ""
	}
	return time.UnixMilli(*ms).In(companyZone).Format("15:04")
}

func hoursOf(ms int64) float64 { return float64(ms/60000) / 60 } // whole minutes, so 7h 59m isn't 7.99999

// GET /v1/admin/calendar/export?month=YYYY-MM → Griha-Attendance-YYYY-MM.xlsx
func (s *Server) attendanceExport(w http.ResponseWriter, r *http.Request) {
	from, to, ok := parseMonth(r.URL.Query().Get("month"))
	if !ok {
		writeError(w, http.StatusBadRequest, "INVALID", "month must be YYYY-MM")
		return
	}
	ctx := r.Context()
	users, err := s.usersForCalendar(ctx, "", from, to)
	if err != nil {
		s.internal(w, "attendance export", err)
		return
	}
	ids := make([]string, len(users))
	for i, u := range users {
		ids[i] = u.ID
	}
	cd, err := s.loadCalendar(ctx, ids, from, to)
	if err != nil {
		s.internal(w, "attendance export", err)
		return
	}
	today := s.today()

	summary := [][]any{{"Employee code", "Name", "Working days", "Days worked", "Hours worked", "Missed check-ins",
		"Missed check-outs", "Leave days", "Casual", "Sick", "Comp-off taken", "Worked on leave", "Comp-offs earned",
		"Comp-off balance (today)"}}
	daily := [][]any{{"Date", "Day", "Employee code", "Name", "Status", "Check-in", "Check-out", "Hours", "Leave",
		"Holiday", "Comp-off", "Notes"}}
	for _, u := range users {
		days, t := cd.buildDays(u, from, to, today)
		bal, err := s.compOffBalance(ctx, u.ID)
		if err != nil {
			s.internal(w, "attendance export", err)
			return
		}
		summary = append(summary, []any{u.EmployeeCode, u.DisplayName, t.WorkingDays, t.DaysWorked, hoursOf(t.WorkedMs),
			t.Missed, t.MissedCheckouts, t.LeaveDays, t.LeaveByKind["CASUAL"], t.LeaveByKind["SICK"], t.LeaveByKind["COMP_OFF"],
			t.WorkedOnLeave, t.CompOffsEarned, bal.Balance})
		for _, d := range days {
			if d.Status == "FUTURE" || (d.Status == "NONE" && d.WorkedMs == 0 && d.FirstIn == nil) {
				continue
			}
			day, _ := parseDay(d.Day)
			status := exportStatusLabel[d.Status]
			if d.Status == "HALF_LEAVE" && d.HalfWork != "" {
				status += "; other half: " + strings.ToLower(exportStatusLabel[d.HalfWork])
			}
			var notes []string
			if d.Overnight {
				notes = append(notes, "shift from the day before")
			}
			if d.ClockSkew {
				notes = append(notes, "phone clock was off")
			}
			if d.PendingLeave != nil {
				notes = append(notes, "leave requested: "+leaveText(d.PendingLeave))
			}
			if d.CompOffCheck != "" {
				notes = append(notes, "check comp-off: "+map[string]string{"SHORT": "under an hour of work", "CLOCK": "phone clock was off"}[d.CompOffCheck])
			}
			compOff := map[string]string{"EARNED": "Earned", "GRANTED": "Granted by admin", "REVOKED": "Taken back by admin"}[d.CompOff]
			var hrs any = ""
			if d.WorkedMs > 0 {
				hrs = hoursOf(d.WorkedMs)
			}
			daily = append(daily, []any{d.Day, day.Format("Mon"), u.EmployeeCode, u.DisplayName, status,
				clockText(d.FirstIn), clockText(d.LastOut), hrs, leaveText(d.Leave), d.Holiday, compOff, strings.Join(notes, "; ")})
		}
	}

	xlsx, err := buildXlsx([]xlsxSheet{
		{name: "Summary", rows: summary, widths: []float64{14, 24, 13, 12, 13, 15, 16, 11, 9, 9, 14, 15, 16, 22}},
		{name: "Day by day", rows: daily, widths: []float64{12, 6, 14, 24, 30, 10, 10, 8, 22, 18, 18, 40}},
	})
	if err != nil {
		s.internal(w, "attendance export", err)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": "Griha-Attendance-" + from.Format(monthLayout) + ".xlsx"}))
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("Content-Length", strconv.Itoa(len(xlsx)))
	_, _ = w.Write(xlsx)
	u := userFrom(r)
	s.auditEvent(ctx, "ATTENDANCE_EXPORT", &u.ID, u.deviceID, "MONTH:"+from.Format(monthLayout), "")
}

// ---- Minimal .xlsx writer: inline strings, numbers, a bold frozen header row ---------------------

type xlsxSheet struct {
	name   string
	rows   [][]any // string | int | float64; the first row is the header
	widths []float64
}

func xlsxCol(i int) string {
	s := ""
	for i++; i > 0; i = (i - 1) / 26 {
		s = string(rune('A'+(i-1)%26)) + s
	}
	return s
}

func (sh xlsxSheet) xml() string {
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
		`<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">` +
		`<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>`)
	if len(sh.widths) > 0 {
		b.WriteString("<cols>")
		for i, wd := range sh.widths {
			fmt.Fprintf(&b, `<col min="%d" max="%d" width="%g" customWidth="1"/>`, i+1, i+1, wd)
		}
		b.WriteString("</cols>")
	}
	b.WriteString("<sheetData>")
	for ri, row := range sh.rows {
		fmt.Fprintf(&b, `<row r="%d">`, ri+1)
		for ci, v := range row {
			ref := xlsxCol(ci) + strconv.Itoa(ri+1)
			style := ""
			if ri == 0 {
				style = ` s="1"`
			}
			switch v := v.(type) {
			case int:
				fmt.Fprintf(&b, `<c r="%s"%s><v>%d</v></c>`, ref, style, v)
			case float64:
				fmt.Fprintf(&b, `<c r="%s" s="2"><v>%s</v></c>`, ref, strconv.FormatFloat(v, 'f', -1, 64))
			case string:
				if v != "" {
					fmt.Fprintf(&b, `<c r="%s" t="inlineStr"%s><is><t xml:space="preserve">%s</t></is></c>`, ref, style, esc(v))
				}
			}
		}
		b.WriteString("</row>")
	}
	b.WriteString("</sheetData></worksheet>")
	return b.String()
}

func buildXlsx(sheets []xlsxSheet) ([]byte, error) {
	var ct, wb, rels strings.Builder
	for i, sh := range sheets {
		fmt.Fprintf(&ct, `<Override PartName="/xl/worksheets/sheet%d.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`, i+1)
		fmt.Fprintf(&wb, `<sheet name="%s" sheetId="%d" r:id="rId%d"/>`, esc(sh.name), i+1, i+1)
		fmt.Fprintf(&rels, `<Relationship Id="rId%d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet%d.xml"/>`, i+1, i+1)
	}
	const head = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`
	files := []struct{ name, body string }{
		{"[Content_Types].xml", head + `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">` +
			`<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>` +
			`<Default Extension="xml" ContentType="application/xml"/>` +
			`<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>` +
			`<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>` +
			ct.String() + `</Types>`},
		{"_rels/.rels", head + `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
			`<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`},
		{"xl/workbook.xml", head + `<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">` +
			`<sheets>` + wb.String() + `</sheets></workbook>`},
		{"xl/_rels/workbook.xml.rels", head + `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` + rels.String() +
			fmt.Sprintf(`<Relationship Id="rId%d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>`, len(sheets)+1) +
			`</Relationships>`},
		// Styles: 0 default, 1 bold header, 2 number with up to two decimals.
		{"xl/styles.xml", head + `<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">` +
			`<numFmts count="1"><numFmt numFmtId="164" formatCode="0.##"/></numFmts>` +
			`<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>` +
			`<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>` +
			`<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>` +
			`<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>` +
			`<cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>` +
			`<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>` +
			`<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>` +
			`</styleSheet>`},
	}
	for i, sh := range sheets {
		files = append(files, struct{ name, body string }{fmt.Sprintf("xl/worksheets/sheet%d.xml", i+1), sh.xml()})
	}
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, f := range files {
		fw, err := zw.Create(f.name)
		if err != nil {
			return nil, err
		}
		if _, err := fw.Write([]byte(f.body)); err != nil {
			return nil, err
		}
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}
