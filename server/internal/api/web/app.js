// Griha web dashboard. Talks to the same /v1 API as the phones, signed in with a cookie session.
// No framework: el() builds DOM nodes (text always via textContent, never innerHTML).
"use strict";

const state = { user: null, tab: "reports", attView: "month", day: null, empSel: null, phoneSel: null, month: monthOf(new Date()), calUser: null, selDay: null, holidaysYear: new Date().getFullYear() };

// ---- Helpers -----------------------------------------------------------------------------------

function el(tag, attrs, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
    else if (k === "class") n.className = v;
    else n.setAttribute(k, v === true ? "" : v);
  }
  for (const k of kids.flat(Infinity)) if (k != null && k !== false) n.append(k instanceof Node ? k : String(k));
  return n;
}
const $ = (id) => document.getElementById(id);
function monthOf(d) { return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0"); }
function addMonths(m, n) { const [y, mo] = m.split("-").map(Number); return monthOf(new Date(y, mo - 1 + n, 1)); }
function monthName(m) { const [y, mo] = m.split("-").map(Number); return new Date(y, mo - 1, 1).toLocaleDateString("en-IN", { month: "long", year: "numeric" }); }
function dayName(d) { return new Date(d + "T00:00:00").toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short", year: "numeric" }); }
function time(ms) { return ms == null ? "—" : new Date(ms).toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit", timeZone: "Asia/Kolkata" }); }
function hours(ms) { if (!ms) return "0h"; const m = Math.round(ms / 60000); return Math.floor(m / 60) + "h " + String(m % 60).padStart(2, "0") + "m"; }
function num(x) { return Number.isInteger(x) ? String(x) : x.toFixed(1); }
function uuid() { return crypto.randomUUID(); } // HTTPS only, which the dashboard always is
function toast(msg) { const t = el("div", { class: "toast" }, msg); document.body.append(t); setTimeout(() => t.remove(), 3500); }
const KIND = { CASUAL: "Casual", SICK: "Sick", COMP_OFF: "Comp-off" };
const HALF = { FIRST: "first half", SECOND: "second half" };
const STATUS_LABEL = { WORKED: "Worked", MISSED_CHECKOUT: "Missed check-out", MISSED: "Missed check-in", LEAVE: "Leave",
  HALF_LEAVE: "Half-day leave", OFF: "Off day", TODAY: "Today", FUTURE: "", NONE: "", COMP_OFF: "Comp-off taken",
  WORKED_ON_LEAVE: "Worked on a leave day", HALF_LEAVE_WORKED: "Half-day leave, worked the other half",
  HALF_LEAVE_MISSED: "Half-day leave, no check-in for the other half", HALF_LEAVE_MISSED_CHECKOUT: "Half-day leave, no check-out" };
const LEAVE_STATUS = { PENDING: "Waiting", APPROVED: "Approved", REJECTED: "Rejected", CANCELLED: "Cancelled", REVOKED: "Taken back" };
// One mark per day, the same as the server's team strip (calendar.go calDay.mark).
function mark(d) {
  if (d.status === "LEAVE" && d.leave && d.leave.kind === "COMP_OFF") return "COMP_OFF";
  if (d.status === "HALF_LEAVE" && d.half_work) return "HALF_LEAVE_" + d.half_work;
  return d.status;
}
function leaveText(l) { return KIND[l.kind] + (l.half ? " (" + HALF[l.half] + ")" : ""); }

class ApiError extends Error { constructor(status, code, msg) { super(msg); this.status = status; this.code = code; } }

async function api(method, path, body) {
  const opts = { method, headers: { "X-Griha-Web": "1" }, credentials: "same-origin" };
  if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
  const r = await fetch(path, opts);
  if (r.status === 204) return null;
  const data = await r.json().catch(() => ({}));
  if (!r.ok) {
    const e = data.error || {};
    if (r.status === 423 && e.code === "APP_LOCKED") { showLocked(e.message); throw new ApiError(423, e.code, e.message); }
    if (r.status === 401 && state.user) { state.user = null; render(); } // session ended
    if (r.status === 403 && e.code === "PASSWORD_CHANGE_REQUIRED") { state.user.must_change_password = true; render(); }
    throw new ApiError(r.status, e.code, e.message || "Something went wrong (" + r.status + ")");
  }
  return data;
}

// Downloads go through fetch so errors (e.g. photos still uploading) show as a message, not a JSON page.
async function download(path, fallbackName, btn) {
  if (btn) { btn.disabled = true; btn.dataset.label = btn.textContent; btn.textContent = "Preparing…"; }
  try {
    const r = await fetch(path, { credentials: "same-origin", headers: { "X-Griha-Web": "1" } });
    if (!r.ok) {
      const e = (await r.json().catch(() => ({}))).error || {};
      if (r.status === 423) return showLocked(e.message);
      throw new Error(e.message || "Download failed (" + r.status + ")");
    }
    const cd = r.headers.get("Content-Disposition") || "";
    const name = (cd.match(/filename="([^"]+)"/) || [])[1] || fallbackName;
    const url = URL.createObjectURL(await r.blob());
    const a = el("a", { href: url, download: name });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  } catch (e) { toast(e.message); }
  finally { if (btn) { btn.disabled = false; btn.textContent = btn.dataset.label; } }
}

function showLocked(msg) {
  $("top").hidden = true;
  $("main").replaceChildren(el("div", { class: "card login center" },
    el("img", { class: "logo", src: "logo.jpg", alt: "" }), el("h1", {}, "App locked"), el("p", { class: "muted" }, msg || "")));
}

// ---- Shell ---------------------------------------------------------------------------------------

async function boot() {
  $("signout").addEventListener("click", async () => {
    try { await api("POST", "/v1/auth/logout"); } catch (_) { /* signed out anyway */ }
    state.user = null; render();
  });
  try { state.user = (await api("GET", "/v1/me")).user; } catch (e) { if (e.status === 423) return; state.user = null; }
  render();
}

function render() {
  const main = $("main");
  if (!state.user) { $("top").hidden = true; main.replaceChildren(loginView()); return; }
  if (state.user.must_change_password) { $("top").hidden = true; main.replaceChildren(passwordView()); return; }
  $("top").hidden = false;
  $("who").textContent = state.user.display_name + " (" + state.user.employee_code + ")";
  const tabs = [["reports", "Reports"]];
  const admin = state.user.role === "ADMIN";
  if (admin) tabs.push(["attendance", "Attendance"], ["leave", "Leave"], ["holidays", "Holidays"], ["employees", "Employees"],
    ["phones", "Phones"], ["backup", "Backup"], ["audit", "Audit log"]);
  if (!tabs.some(t => t[0] === state.tab)) state.tab = "reports";
  $("tabs").replaceChildren(...tabs.map(([k, label]) => el("button", { class: k === state.tab ? "on" : "", onclick: () => { state.tab = k; render(); } },
    label, k === "leave" && state.pendingLeave ? el("span", { class: "badge" }, state.pendingLeave) : null)));
  if (admin) refreshPendingBadge();
  if (!main.firstElementChild || main.dataset.tab !== state.tab) main.replaceChildren(el("p", { class: "empty" }, "Loading…"));
  main.dataset.tab = state.tab;
  main.classList.add("busy");
  // On a slow link an older response can arrive after a newer one: only the latest render draws.
  const seq = ++renderSeq;
  const views = { reports: reportsView, attendance: attendanceView, leave: leaveView, holidays: holidaysView,
    employees: employeesView, phones: phonesView, backup: backupView, audit: auditView };
  views[state.tab]().then(v => {
    if (seq !== renderSeq) return;
    main.classList.remove("busy");
    if (v) { main.replaceChildren(v); if (v.afterRender) v.afterRender(); }
  }).catch(e => {
    if (seq !== renderSeq) return;
    main.classList.remove("busy");
    if (e.status !== 423 && e.status !== 401) main.replaceChildren(el("p", { class: "err center" }, e.message));
  });
}
let renderSeq = 0;

// Leave requests waiting for the admin, shown on the Leave tab. Checked at most once a minute.
let pendingCheckedAt = 0;
async function refreshPendingBadge(force) {
  if (!force && Date.now() - pendingCheckedAt < 60000) return;
  pendingCheckedAt = Date.now();
  try {
    const n = (await api("GET", "/v1/admin/leave?status=PENDING&limit=1")).pending;
    if (n !== state.pendingLeave) { state.pendingLeave = n; renderTabsOnly(); }
  } catch (_) { /* the badge is a hint only */ }
}
function renderTabsOnly() {
  for (const b of $("tabs").children) {
    if (b.textContent.startsWith("Leave")) b.replaceChildren("Leave", state.pendingLeave ? el("span", { class: "badge" }, state.pendingLeave) : "");
  }
}

function loginView() {
  const msg = el("p", { class: "err small" });
  const code = el("input", { autocomplete: "username", required: true, autocapitalize: "characters" });
  const pass = el("input", { type: "password", autocomplete: "current-password", required: true });
  const btn = el("button", { class: "btn", type: "submit" }, "Sign in");
  const form = el("form", { onsubmit: async (ev) => {
    ev.preventDefault(); btn.disabled = true; msg.textContent = "";
    try { state.user = (await api("POST", "/v1/auth/web-login", { employee_code: code.value, password: pass.value })).user; render(); }
    catch (e) { msg.textContent = e.message; btn.disabled = false; }
  } }, el("label", { class: "f" }, "Employee code", code), el("label", { class: "f" }, "Password", pass), btn, msg);
  return el("div", { class: "card login" }, el("img", { class: "logo", src: "logo.jpg", alt: "" }),
    el("h1", {}, "Griha dashboard"), el("p", { class: "muted" }, "Sign in with your employee code and app password."), form);
}

function passwordView() {
  const msg = el("p", { class: "err small" });
  const cur = el("input", { type: "password", autocomplete: "current-password", required: true });
  const nw = el("input", { type: "password", autocomplete: "new-password", required: true, minlength: "10" });
  const form = el("form", { onsubmit: async (ev) => {
    ev.preventDefault(); msg.textContent = "";
    try {
      await api("POST", "/v1/auth/password", { current_password: cur.value, new_password: nw.value });
      state.user.must_change_password = false; render();
      toast("Password changed. Your phone will ask you to sign in again with the new password when it is next online.");
    }
    catch (e) { msg.textContent = e.message; }
  } }, el("label", { class: "f" }, "Current (temporary) password", cur), el("label", { class: "f" }, "New password (at least 10 characters)", nw),
    el("button", { class: "btn", type: "submit" }, "Change password"), msg);
  return el("div", { class: "card login" }, el("h1", {}, "Choose a new password"), form);
}

// ---- Reports (everyone) --------------------------------------------------------------------------

let reportsQuery = "";
let reportsCaret = null; // the search box's selection while typing, restored after the list redraws
async function reportsView() {
  const data = await api("GET", "/v1/reports?q=" + encodeURIComponent(reportsQuery));
  const search = el("input", { type: "search", placeholder: "Search client, address or inspector", value: reportsQuery, class: "w280" });
  let timer;
  search.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => { reportsQuery = search.value.trim(); reportsCaret = [search.selectionStart, search.selectionEnd]; render(); }, 400);
  });
  const tbody = el("tbody", {}, data.inspections.map(reportRow));
  let next = data.next;
  const more = el("button", { class: "btn ghost more", hidden: !next }, "Load more");
  more.addEventListener("click", async () => {
    more.disabled = true;
    try {
      const page = await api("GET", "/v1/reports?q=" + encodeURIComponent(reportsQuery) + "&before=" + encodeURIComponent(next));
      tbody.append(...page.inspections.map(reportRow));
      next = page.next;
      more.hidden = !next;
    } catch (e) { toast(e.message); }
    finally { more.disabled = false; }
  });
  const view = el("div", {},
    el("div", { class: "pagehead" }, el("div", {}, el("h1", {}, "Reports"), el("p", { class: "lede" }, "Download any inspection's report as a PDF, or as a Word file to edit.")), search),
    el("div", { class: "sheet scrollx" }, data.inspections.length ? el("table", { class: "tbl" },
      el("thead", {}, el("tr", {}, ["Client and property", "Report", "Inspected", "Inspector", "Status", ""].map(h => el("th", {}, h)))),
      tbody) : el("p", { class: "empty" }, reportsQuery ? "No inspections match “" + reportsQuery + "”." : "No inspections yet."), more),
    el("p", { class: "foot" }, "The PDF is the one finalized on the phone. The Word file is built on the server from what that PDF showed."));
  view.afterRender = () => {
    if (!reportsCaret) return;
    search.focus();
    search.setSelectionRange(...reportsCaret);
    reportsCaret = null;
  };
  return view;
}

function reportRow(i) {
  const r = i.report;
  let actions;
  if (!r) actions = el("span", { class: "muted small" }, "No report yet");
  else {
    // Version picker: newest first. PDF for every stored version; Word where the server can build it.
    const pick = el("select", { class: "verpick", "aria-label": "Report version" },
      i.versions.map((v, n) => el("option", { value: v.id }, "Version " + v.version + (n === 0 ? " (latest)" : ""))));
    const pdf = el("button", { class: "btn small" }, "PDF");
    const word = el("button", { class: "btn small ghost" }, "Word");
    const why = el("div", { class: "small muted" });
    const sync = () => {
      const v = i.versions.find(x => x.id === pick.value);
      pdf.disabled = v.pdf !== "AVAILABLE";
      pdf.textContent = v.pdf === "ARCHIVED" ? "PDF archived" : v.pdf === "UPLOADING" ? "PDF uploading" : "PDF";
      word.disabled = !v.docx;
      word.title = v.docx_reason || "";
      why.textContent = v.docx ? "" : v.docx_reason || "";
      return v;
    };
    pick.addEventListener("change", sync);
    sync();
    pdf.addEventListener("click", () => { const v = sync(); download("/v1/reports/" + v.id + "/pdf", "Griha-Report-" + i.report_number + "-v" + v.version + ".pdf", pdf); });
    word.addEventListener("click", () => { const v = sync(); download("/v1/reports/" + v.id + "/docx", "Griha-Report-" + i.report_number + "-v" + v.version + ".docx", word); });
    actions = el("div", {}, el("div", { class: "row nowrap" }, i.versions.length > 1 ? pick : el("span", { class: "muted small" }, "Version " + r.version), pdf, word), why);
  }
  return el("tr", {},
    el("td", {}, el("b", {}, i.client_name), el("div", { class: "small muted" }, i.property_address)),
    el("td", {}, i.report_number, r ? el("div", { class: "small muted" }, i.versions.length + (i.versions.length === 1 ? " version" : " versions")) : null),
    el("td", {}, dayName(i.inspection_date)),
    el("td", {}, i.inspector || "—"),
    el("td", {}, el("span", { class: "chip" }, { DRAFT: "Draft", IN_PROGRESS: "In progress", READY: "Ready", FINALIZED: "Finalized" }[i.status] || i.status)),
    el("td", {}, actions));
}

// ---- Attendance (admin) ----------------------------------------------------------------------------

const LEGEND = [["WORKED", "Worked"], ["LEAVE", "Leave"], ["HALF_LEAVE_WORKED", "Half day + worked"], ["HALF_LEAVE_MISSED", "Half day, other half missed"],
  ["WORKED_ON_LEAVE", "Worked on a leave day"], ["COMP_OFF", "Comp-off taken"], ["MISSED", "Missed check-in"], ["MISSED_CHECKOUT", "Missed check-out"],
  ["OFF", "Sunday or holiday"]];

function legend(withPending) {
  return el("div", { class: "legend" },
    LEGEND.map(([k, l]) => el("span", {}, el("i", { class: "tile " + k }), l)),
    el("span", {}, el("b", { class: "star" }, "★"), "Comp-off earned"),
    withPending ? el("span", {}, el("b", { class: "warn" }, "•"), "Leave requested") : null);
}

function monthBar(onChange) {
  const go = (n) => { state.month = addMonths(state.month, n); onChange(); };
  return el("div", { class: "monthbar" },
    el("button", { class: "iconbtn", "aria-label": "Previous month", onclick: () => go(-1) }, "‹"),
    el("span", { class: "monthname" }, monthName(state.month)),
    el("button", { class: "iconbtn", "aria-label": "Next month", onclick: () => go(1) }, "›"),
    state.month !== monthOf(new Date()) ? el("button", { class: "link small", onclick: () => { state.month = monthOf(new Date()); onChange(); } }, "This month") : null);
}

function initials(name) { return name.split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0].toUpperCase()).join(""); }

const COMP_CHECK = { SHORT: "under an hour of work", CLOCK: "the phone's clock was off" };

// Team month: one row per engineer, one tile per day — the dashboard's main picture.
// Month calendar or one day's check-ins: the same tab, two views.
function attSwitch() {
  const b = (k, label) => el("button", { class: state.attView === k ? "on" : "", onclick: () => { state.attView = k; state.calUser = null; render(); } }, label);
  return el("div", { class: "seg" }, b("month", "Month"), b("day", "By day"));
}

async function attendanceView() {
  if (state.attView === "day") return attendanceDayView();
  if (state.calUser) return personView();
  const month = state.month;
  const data = await api("GET", "/v1/admin/calendar/team?month=" + month);
  const [y, m] = month.split("-").map(Number);
  const nDays = new Date(y, m, 0).getDate();
  const holidays = Object.fromEntries((data.holidays || []).map(h => [h.day, h.name]));
  const dayKey = (d) => month + "-" + String(d).padStart(2, "0");
  const cols = [];
  for (let d = 1; d <= nDays; d++) {
    const dow = new Date(y, m - 1, d).getDay();
    cols.push({ d, key: dayKey(d), sun: dow === 0, wd: "SMTWTFS"[dow], today: dayKey(d) === data.today, holiday: holidays[dayKey(d)] });
  }
  const colCls = (c) => ["day", c.sun || c.holiday ? "offcol" : "", c.today ? "todaycol" : ""].join(" ");
  const head = el("tr", {}, el("th", { class: "who" }, ""),
    cols.map(c => el("th", { class: colCls(c), title: c.holiday || "" }, el("span", { class: "wd" }, c.wd), el("span", { class: "dn" }, c.d))),
    ["Days", "Hours", "Leave", "Missed"].map(h => el("th", { class: "num" }, h)));
  const rows = data.users.map(u => {
    const open = () => { state.calUser = u.user; state.selDay = null; render(); };
    const t = u.totals, missed = t.missed + t.missed_checkouts;
    const pending = new Set(u.pending || []);
    return el("tr", { class: "person", tabindex: "0", onclick: open, onkeydown: (e) => { if (e.key === "Enter") open(); } },
      el("td", { class: "who" }, el("span", { class: "avatar" }, initials(u.user.display_name)),
        el("span", { class: "nm" }, el("b", {}, u.user.display_name), el("small", {}, u.user.employee_code))),
      u.days.map((st, i) => el("td", { class: colCls(cols[i]) + (pending.has(cols[i].key) ? " pend" : ""),
        title: dayName(cols[i].key) + (STATUS_LABEL[st] ? " — " + STATUS_LABEL[st] : "") + (pending.has(cols[i].key) ? " — leave requested" : "") },
        el("i", { class: "tile " + st }))),
      el("td", { class: "num strong" }, t.days_worked),
      el("td", { class: "num", title: hours(t.worked_ms) }, Math.round(t.worked_ms / 3600000) + "h"),
      el("td", { class: "num" + (t.leave_days ? " red" : "") }, num(t.leave_days)),
      el("td", { class: "num" + (missed ? " amber" : "") }, missed));
  });
  // Comp-offs of this month, newest first, so new ones (and odd ones) get noticed.
  const comps = data.comp_offs || [];
  const compList = comps.length ? el("div", { class: "sheet mt12" },
    el("h2", {}, "Comp-offs this month"),
    el("table", { class: "tbl" }, el("tbody", {}, comps.map(c => el("tr", {},
      el("td", {}, el("b", {}, c.display_name)),
      el("td", {}, dayName(c.day)),
      el("td", {}, c.kind === "GRANTED" ? "Granted by admin" : "Worked " + hours(c.worked_ms)),
      el("td", {}, c.check ? el("span", { class: "warn" }, "Check: " + COMP_CHECK[c.check]) : null),
      el("td", {}, el("button", { class: "btn small ghost", onclick: () => {
        state.calUser = data.users.find(u => u.user.id === c.user_id).user; state.selDay = c.day; render();
      } }, "Open")))))),
    el("p", { class: "foot" }, "Working on a Sunday or holiday earns a comp-off automatically. Open the day to take one back.")) : null;
  return el("div", {},
    el("div", { class: "pagehead" },
      el("div", {}, el("h1", {}, "Attendance"), el("p", { class: "lede" }, data.users.length + (data.users.length === 1 ? " engineer" : " engineers") + ". Select one to see their month.")),
      el("div", { class: "row" }, attSwitch(), monthBar(render), excelButton(month))),
    legend(true),
    el("div", { class: "sheet scrollx" }, rows.length ? el("table", { class: "strip" }, el("thead", {}, head), el("tbody", {}, rows))
      : el("p", { class: "empty" }, "No tracked engineers this month. In the app, Profile → Employees decides who is tracked and from which day.")),
    compList);
}

// The month as an Excel file: a Summary sheet (payroll) and a Day by day sheet (check-in/out times).
function excelButton(month) {
  const b = el("button", { class: "btn small ghost", title: "Summary and day-by-day sheets for " + monthName(month),
    onclick: () => download("/v1/admin/calendar/export?month=" + month, "Griha-Attendance-" + month + ".xlsx", b) }, "Download Excel");
  return b;
}

async function personView() {
  const u = state.calUser;
  const cal = await api("GET", "/v1/admin/calendar?month=" + state.month + "&user=" + u.id);
  const days = cal.days;
  if (state.selDay && !days.some(d => d.day === state.selDay)) state.selDay = null;
  const first = new Date(days[0].day + "T00:00:00").getDay(); // 0 = Sunday
  const lead = (first + 6) % 7; // weeks start on Monday
  const grid = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map(d => el("div", { class: "dow" + (d === "Sun" ? " sun" : "") }, d));
  for (let i = 0; i < lead; i++) grid.push(el("div", { class: "cell blank" }));
  for (const d of days) {
    const st = mark(d);
    const cls = ["cell", "s-" + st, d.off_day ? "off" : "", d.day === cal.today ? "today" : "", d.day === state.selDay ? "sel" : ""].join(" ");
    const note = d.first_in || d.worked_ms ? (st === "MISSED_CHECKOUT" || d.half_work === "MISSED_CHECKOUT" ? "No check-out" : hours(d.worked_ms))
      : d.leave ? leaveText(d.leave) : st === "MISSED" ? "No check-in" : null;
    grid.push(el("button", { class: cls, onclick: () => { state.selDay = d.day; render(); }, "aria-label": dayName(d.day) + " " + (STATUS_LABEL[st] || "") },
      el("span", { class: "n" }, Number(d.day.slice(8)), d.comp_off === "EARNED" || d.comp_off === "GRANTED" ? el("b", { class: "star", title: "Comp-off earned" }, "★") : null,
        d.clock_skew ? el("b", { class: "warn", title: "The phone's clock was off" }, " ⚠") : null),
      ["FUTURE", "NONE", "TODAY", "OFF"].includes(st) ? null : el("i", { class: "tile " + st }),
      note ? el("span", { class: "t" }, note) : null,
      d.holiday ? el("span", { class: "h" }, d.holiday) : d.pending_leave ? el("span", { class: "h" }, "Leave requested") : null));
  }
  const t = cal.totals, co = cal.comp_off;
  const kinds = Object.entries(t.leave_by_kind).map(([k, v]) => KIND[k] + " " + num(v)).join(", ");
  const figure = (v, l, cls) => el("div", { class: "fig " + (cls || "") }, el("b", {}, v), el("span", {}, l));
  const who = cal.user;
  const trackNote = !who.track_attendance ? "Not tracked: missing check-ins don't count. Change it in the app, Profile → Employees."
    : "Tracked from " + dayName(who.attendance_from) + (who.left_on ? " until " + dayName(who.left_on) : "") + ".";
  return el("div", {},
    el("button", { class: "link back", onclick: () => { state.calUser = null; render(); } }, "‹ All engineers"),
    el("div", { class: "pagehead" },
      el("div", { class: "who big" }, el("span", { class: "avatar" }, initials(u.display_name)),
        el("span", { class: "nm" }, el("h1", {}, u.display_name), el("small", {}, u.employee_code + " · " + trackNote))),
      monthBar(render)),
    el("div", { class: "figures" },
      figure(t.days_worked, "days worked" + (t.worked_on_leave ? " (" + t.worked_on_leave + " on leave days)" : ""), "green"), figure(hours(t.worked_ms), "on duty"),
      figure(num(t.leave_days), kinds ? "leave days (" + kinds + ")" : "leave days", t.leave_days ? "red" : ""),
      figure(t.missed, "missed check-ins", t.missed ? "amber" : ""), figure(t.missed_checkouts, "missed check-outs", t.missed_checkouts ? "amber" : ""),
      figure(num(co.balance), "comp-offs available (" + co.earned + " earned, " + num(co.used) + " used)", co.balance < 0 ? "red" : "blue")),
    el("div", { class: "split" },
      el("div", { class: "sheet" }, el("div", { class: "cal" }, grid), legend(false)),
      el("aside", { class: "sheet panel" }, state.selDay ? dayPanel(u, days.find(d => d.day === state.selDay))
        : el("p", { class: "empty" }, "Select a day to see check-in times, leave and comp-off."))));
}

function dayPanel(u, d) {
  const st = mark(d);
  const items = [["Status", STATUS_LABEL[st] || (d.status === "FUTURE" ? "Upcoming" : d.status === "NONE" ? "Not tracked" : "—")]];
  if (d.holiday) items.push(["Holiday", d.holiday]);
  if (d.first_in || d.worked_ms) {
    items.push(["Check-in", d.first_in ? time(d.first_in) : "the day before"], ["Check-out", d.last_out ? time(d.last_out) : "—"], ["Worked", hours(d.worked_ms)]);
  }
  if (d.overnight) items.push(["Shift", "Continued from the previous day (counted until midnight there, from midnight here)"]);
  if (d.clock_skew) items.push(["Clock", "The phone's clock was off by more than 5 minutes; times shown are the server's estimate"]);
  if (d.leave) items.push(["Leave", leaveText(d.leave) + (d.leave.reason ? " — " + d.leave.reason : "")]);
  if (d.status === "WORKED_ON_LEAVE") items.push(["Note", "Worked on an approved leave day: counted as worked, not as leave. Take the leave back under Leave if it no longer applies."]);
  if (d.pending_leave) items.push(["Requested", leaveText(d.pending_leave) + " (waiting for you, see Leave)"]);
  if (d.comp_off) {
    items.push(["Comp-off", { EARNED: "Earned (worked on an off day)", GRANTED: "Granted by admin", REVOKED: "Taken back by admin" }[d.comp_off]
      + (d.comp_off_check ? " — check: " + COMP_CHECK[d.comp_off_check] : "")]);
  }
  const compBtns = [];
  const setComp = async (action) => {
    try { await api("POST", "/v1/admin/comp-offs", { user_id: u.id, day: d.day, action }); toast("Comp-off updated"); render(); } catch (e) { toast(e.message); }
  };
  if (d.comp_off === "EARNED") compBtns.push(el("button", { class: "btn small ghost", onclick: () => setComp("REVOKE") }, "Take back comp-off"));
  else if (d.comp_off === "REVOKED" || d.comp_off === "GRANTED") compBtns.push(el("button", { class: "btn small ghost", onclick: () => setComp("CLEAR") }, d.comp_off === "GRANTED" ? "Remove comp-off" : "Restore comp-off"));
  else compBtns.push(el("button", { class: "btn small ghost", onclick: () => setComp("GRANT") }, "Grant comp-off for this day"));
  return el("div", {},
    el("h2", {}, dayName(d.day)),
    el("dl", {}, items.map(([k, v]) => [el("dt", {}, k), el("dd", {}, v)])),
    el("div", { class: "row" }, compBtns),
    d.leave || d.off_day ? null : el("div", { class: "subsection" }, el("h2", {}, "Record leave"), recordLeaveForm(u, d.day)));
}

function recordLeaveForm(u, day) {
  const kind = el("select", {}, Object.entries(KIND).map(([k, l]) => el("option", { value: k }, l)));
  const start = el("input", { type: "date", value: day || "" });
  const end = el("input", { type: "date", value: day || "" });
  const half = el("select", {}, el("option", { value: "" }, "Full day"), el("option", { value: "FIRST" }, "First half"), el("option", { value: "SECOND" }, "Second half"));
  const reason = el("input", { placeholder: "Optional" });
  const msg = el("p", { class: "err small" });
  const btn = el("button", { class: "btn", type: "submit" }, "Record approved leave");
  return el("form", { class: "stack", onsubmit: async (ev) => {
    ev.preventDefault(); msg.textContent = ""; btn.disabled = true;
    try {
      await api("POST", "/v1/admin/leave", { id: uuid(), user_id: u.id, kind: kind.value, start_day: start.value, end_day: end.value || start.value,
        half: half.value || null, reason: reason.value });
      toast("Leave recorded (approved)"); render();
    } catch (e) { msg.textContent = e.message; btn.disabled = false; }
  } }, el("div", { class: "pair" }, el("label", { class: "f" }, "Type", kind), el("label", { class: "f" }, "Length", half)),
    el("div", { class: "pair" }, el("label", { class: "f" }, "From", start), el("label", { class: "f" }, "To", end)),
    el("label", { class: "f" }, "Reason", reason), btn, msg);
}

// ---- Leave (admin) ---------------------------------------------------------------------------------

// What the admin should know before deciding: days the engineer worked anyway, and the comp-off balance.
function leaveWarnings(l) {
  const out = [];
  if (l.worked_days) out.push("Checked in on " + l.worked_days + " of these days.");
  if (l.kind === "COMP_OFF" && l.comp_off) {
    const b = l.comp_off.balance;
    out.push(l.status === "APPROVED" ? "Comp-offs available: " + num(b) + "." :
      "Comp-offs available: " + num(b) + " → " + num(b - l.days) + " after approval." + (b - l.days < 0 ? " (more than earned)" : ""));
  }
  return out;
}

let leaveFilter = "PENDING";
async function leaveView() {
  const data = await api("GET", "/v1/admin/leave" + (leaveFilter ? "?status=" + leaveFilter : ""));
  const filter = el("select", { onchange: (e) => { leaveFilter = e.target.value; render(); } },
    [["PENDING", "Waiting for approval"], ["", "All"], ["APPROVED", "Approved"], ["REJECTED", "Rejected"], ["REVOKED", "Taken back"], ["CANCELLED", "Cancelled"]]
      .map(([v, l]) => el("option", { value: v, selected: v === leaveFilter }, l)));
  const rows = data.requests.map(l => {
    const note = el("input", { placeholder: "Note (optional)", class: "full" });
    const decide = async (approve, done) => {
      try {
        await api("POST", "/v1/admin/leave/" + l.id + "/decision", { approve, note: note.value });
        toast(done); refreshPendingBadge(true); render();
      } catch (e) { toast(e.message); }
    };
    let actions = null;
    if (l.status === "PENDING") actions = el("div", {}, note, el("div", { class: "row mt6" },
      el("button", { class: "btn small", onclick: () => decide(true, "Approved") }, "Approve"),
      el("button", { class: "btn small red", onclick: () => decide(false, "Rejected") }, "Reject")));
    else if (l.status === "APPROVED") actions = el("button", { class: "btn small ghost", onclick: () => { if (confirm("Take back this approved leave?")) decide(false, "Taken back"); } }, "Take back");
    else if (l.status === "REJECTED" || l.status === "REVOKED") actions = el("button", { class: "btn small ghost", onclick: () => { if (confirm("Approve this leave after all?")) decide(true, "Approved"); } }, "Approve after all");
    const span = l.start_day === l.end_day ? dayName(l.start_day) : dayName(l.start_day) + " – " + dayName(l.end_day);
    const warnings = leaveWarnings(l);
    return el("tr", {},
      el("td", {}, el("b", {}, l.display_name), el("div", { class: "small muted" }, l.employee_code)),
      el("td", {}, leaveText(l)),
      el("td", {}, span, el("div", { class: "small muted" }, num(l.days) + (l.days === 1 ? " day" : " days")),
        warnings.map(w => el("div", { class: "small warn" }, w))),
      el("td", {}, l.reason || el("span", { class: "muted" }, "—"), l.by_admin ? el("div", { class: "small muted" }, "Recorded by admin") : null),
      el("td", {}, el("span", { class: "chip " + l.status }, LEAVE_STATUS[l.status] || l.status),
        l.decided_by ? el("div", { class: "small muted" }, "by " + l.decided_by + (l.decision_note ? ": " + l.decision_note : "")) : null),
      el("td", { class: "w200" }, actions));
  });
  return el("div", {},
    el("div", { class: "pagehead" }, el("div", {}, el("h1", {}, "Leave"),
      el("p", { class: "lede" }, data.pending ? data.pending + (data.pending === 1 ? " request is" : " requests are") + " waiting for you." : "No requests waiting.")), filter),
    el("div", { class: "sheet scrollx" }, rows.length ? el("table", { class: "tbl" },
      el("thead", {}, el("tr", {}, ["Engineer", "Type", "Dates", "Reason", "Status", ""].map(h => el("th", {}, h)))), el("tbody", {}, rows))
      : el("p", { class: "empty" }, "No requests here.")),
    el("p", { class: "foot" }, "Engineers request leave in the app. To record leave yourself, open Attendance, select the engineer, then the day."));
}

// ---- Holidays (admin) -------------------------------------------------------------------------------

function affectsText(n) { return n ? " It changes the comp-offs or leave days of " + n + (n === 1 ? " person." : " people.") : ""; }

async function holidaysView() {
  const y = state.holidaysYear;
  const data = await api("GET", "/v1/holidays?year=" + y);
  const day = el("input", { type: "date", required: true, min: y + "-01-01", max: y + "-12-31" });
  const name = el("input", { placeholder: "Name, e.g. Diwali", required: true, maxlength: "100" });
  const msg = el("p", { class: "err small" });
  const form = el("form", { class: "row", onsubmit: async (ev) => {
    ev.preventDefault(); msg.textContent = "";
    try { const r = await api("PUT", "/v1/admin/holidays/" + day.value, { name: name.value }); toast("Holiday saved." + affectsText(r.affects)); render(); }
    catch (e) { msg.textContent = e.message; }
  } }, day, name, el("button", { class: "btn", type: "submit" }, "Add holiday"));
  const rows = data.holidays.map(h => el("tr", {}, el("td", {}, dayName(h.day)), el("td", {}, h.name),
    el("td", {}, el("button", { class: "btn small ghost", onclick: async () => {
      if (!confirm("Remove " + h.name + "? " + dayName(h.day) + " becomes a working day again.")) return;
      try { const r = await api("DELETE", "/v1/admin/holidays/" + h.day); toast(h.name + " removed." + affectsText(r && r.affects)); render(); } catch (e) { toast(e.message); }
    } }, "Remove"))));
  return el("div", {},
    el("div", { class: "pagehead" }, el("div", {}, el("h1", {}, "Holidays"),
      el("p", { class: "lede" }, "Sunday is the weekly off. On a holiday nobody is marked missed, and working earns a comp-off.")),
      el("div", { class: "monthbar" },
        el("button", { class: "iconbtn", "aria-label": "Previous year", onclick: () => { state.holidaysYear--; render(); } }, "‹"),
        el("span", { class: "monthname" }, y),
        el("button", { class: "iconbtn", "aria-label": "Next year", onclick: () => { state.holidaysYear++; render(); } }, "›"))),
    el("div", { class: "sheet" }, form, msg,
      rows.length ? el("table", { class: "tbl mt12" }, el("tbody", {}, rows)) : el("p", { class: "empty" }, "No holidays for " + y + " yet.")));
}

// ---- Admin pages brought from the app: attendance by day, employees, phones, backup, audit ------------

const IST_MS = 5.5 * 3600 * 1000;
function todayIST() { return new Date(Date.now() + IST_MS).toISOString().slice(0, 10); }
function addDays(day, n) { const d = new Date(day + "T00:00:00Z"); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); }
function dayStartMs(day) { return Date.parse(day + "T00:00:00Z") - IST_MS; } // midnight in India
function bytes(n) {
  if (n == null || n < 0) return "—";
  if (n >= 1e9) return (n / 1e9).toFixed(1) + " GB";
  if (n >= 1e6) return Math.round(n / 1e6) + " MB";
  if (n >= 1e3) return Math.round(n / 1e3) + " KB";
  return n + " B";
}
function when(ms) {
  if (!ms) return "never";
  const d = new Date(ms);
  return d.toLocaleDateString("en-IN", { day: "numeric", month: "short", timeZone: "Asia/Kolkata" }) + ", " + time(ms);
}
function ago(ms) {
  if (!ms) return "never";
  const m = Math.round((Date.now() - ms) / 60000);
  if (m < 1) return "just now";
  if (m < 60) return m + " min ago";
  if (m < 48 * 60) return Math.round(m / 60) + " h ago";
  return Math.round(m / 1440) + " days ago";
}
function sidePanel(content, onClose) {
  return el("aside", { class: "sheet panel" }, el("button", { class: "link close", onclick: onClose, "aria-label": "Close" }, "Close"), content);
}

// ---- Attendance by day ----

const FACE = { MATCH: "Face matched", WEAK: "Face: weak match", NO_MATCH: "Face did not match", NOT_ENROLLED: "No face set up", UNAVAILABLE: "Face check unavailable" };

function recordFlags(r) {
  const f = [];
  if (r.face_result !== "MATCH") f.push([FACE[r.face_result] || r.face_result, r.face_result === "NO_MATCH" ? "bad" : "warn"]);
  if (!r.liveness_passed) f.push(["No head-turn check", "warn"]);
  if (r.is_mock) f.push(["Fake GPS app", "bad"]);
  if (r.lat == null) f.push(["No location", "warn"]);
  if (r.estimated_at != null && Math.abs(r.estimated_at - r.client_at) > 5 * 60000) f.push(["Phone clock off", "bad"]);
  return f;
}

async function attendanceDayView() {
  const day = state.day || todayIST();
  const [att, users] = await Promise.all([
    api("GET", "/v1/admin/attendance?from=" + dayStartMs(day) + "&to=" + dayStartMs(addDays(day, 1))),
    api("GET", "/v1/admin/users"),
  ]);
  const byUser = {};
  for (const r of att.records) (byUser[r.user_id] = byUser[r.user_id] || []).push(r);
  const people = users.users.filter(u => u.status === "ACTIVE" && (u.track_attendance || byUser[u.id]))
    .map(u => ({ u, recs: (byUser[u.id] || []).sort((a, b) => a.client_at - b.client_at) }));
  for (const id of Object.keys(byUser)) if (!people.some(p => p.u.id === id)) {
    const r = byUser[id][0];
    people.push({ u: { id, display_name: r.display_name, employee_code: r.employee_code }, recs: byUser[id] });
  }
  const summary = (p) => {
    let worked = 0, open = null;
    for (const r of p.recs) {
      if (r.kind === "IN" && open == null) open = r.client_at;
      else if (r.kind === "OUT" && open != null) { worked += r.client_at - open; open = null; }
    }
    if (open != null && day === todayIST()) worked += Date.now() - open; // on duty now: count up to now, like the app
    return { firstIn: p.recs.find(r => r.kind === "IN"), lastOut: [...p.recs].reverse().find(r => r.kind === "OUT"), worked, onDuty: open != null,
      flagged: p.recs.some(r => recordFlags(r).length) };
  };
  people.forEach(p => p.s = summary(p));
  people.sort((a, b) => (a.recs.length ? 0 : 1) - (b.recs.length ? 0 : 1) || (b.s.flagged - a.s.flagged) || a.u.display_name.localeCompare(b.u.display_name));
  const came = people.filter(p => p.recs.length).length;
  const go = (n) => { state.day = addDays(day, n); render(); };
  const picker = el("input", { type: "date", value: day, max: todayIST(), onchange: (e) => { if (e.target.value) { state.day = e.target.value; render(); } } });
  const rows = people.map(p => {
    const open = state.daySel === p.u.id;
    const s = p.s;
    const main = el("tr", { class: "person" + (open ? " sel" : ""), tabindex: "0", onclick: () => { state.daySel = open ? null : p.u.id; render(); } },
      el("td", {}, el("div", { class: "who" },
        s.firstIn && s.firstIn.selfie === "AVAILABLE" ? el("img", { class: "selfie sm", src: "/v1/attendance/" + s.firstIn.id + "/selfie", alt: "" })
          : el("span", { class: "avatar" }, initials(p.u.display_name)),
        el("span", { class: "nm" }, el("b", {}, p.u.display_name), el("small", {}, p.u.employee_code)))),
      el("td", {}, s.firstIn ? time(s.firstIn.client_at) : el("span", { class: "muted" }, "Not checked in")),
      el("td", {}, s.onDuty ? el("span", { class: "chip APPROVED" }, "On duty") : s.lastOut ? time(s.lastOut.client_at) : "—"),
      el("td", { class: "num" }, p.recs.length ? hours(s.worked) : "—"),
      el("td", {}, s.flagged ? el("span", { class: "chip REJECTED" }, "Review") : p.recs.length ? el("span", { class: "muted small" }, "All checks passed") : null));
    if (!open || !p.recs.length) return main;
    const enrol = p.recs.map(r => r.enrollment_id).find(Boolean);
    const detail = el("tr", { class: "detail" }, el("td", { colspan: "5" },
      el("div", { class: "recs" },
        enrol ? el("figure", { class: "ref" }, el("img", { class: "selfie lg", src: "/v1/face/enrollments/" + enrol + "/photo", alt: "Approved face" }),
          el("figcaption", {}, "Approved face")) : el("p", { class: "muted small" }, "No approved face for these check-ins: compare the selfies yourself."),
        p.recs.map(r => el("figure", { class: "rec" },
          r.selfie === "AVAILABLE" ? el("img", { class: "selfie lg", src: "/v1/attendance/" + r.id + "/selfie", alt: "Selfie" })
            : el("div", { class: "selfie lg none" }, r.selfie === "DELETED" ? "Selfie removed after 90 days" : "Selfie uploading"),
          el("figcaption", {},
            el("b", {}, (r.kind === "IN" ? "Checked in " : "Checked out ") + time(r.client_at)),
            r.lat != null ? el("a", { href: "https://www.google.com/maps?q=" + r.lat + "," + r.lng, target: "_blank", rel: "noopener noreferrer" },
              "Open in Maps (±" + Math.round(r.accuracy_m || 0) + " m)") : null,
            r.face_score != null ? el("span", { class: "small muted" }, "Face similarity " + r.face_score.toFixed(2)) : null,
            el("span", { class: "flags" }, recordFlags(r).map(([t, k]) => el("span", { class: "flag " + k }, t)))))))));
    return [main, detail];
  });
  return el("div", {},
    el("div", { class: "pagehead" },
      el("div", {}, el("h1", {}, "Attendance"), el("p", { class: "lede" }, came + " of " + people.length + " checked in on " + dayName(day) + ".")),
      el("div", { class: "row" }, attSwitch(),
        el("div", { class: "monthbar" },
          el("button", { class: "iconbtn", "aria-label": "Previous day", onclick: () => go(-1) }, "‹"), picker,
          el("button", { class: "iconbtn", "aria-label": "Next day", disabled: day >= todayIST(), onclick: () => go(1) }, "›")))),
    el("div", { class: "sheet scrollx" }, people.length ? el("table", { class: "tbl" },
      el("thead", {}, el("tr", {}, ["Engineer", "Check-in", "Check-out", "Worked", "Checks"].map(h => el("th", { class: h === "Worked" ? "num" : "" }, h)))),
      el("tbody", {}, rows)) : el("p", { class: "empty" }, "Nobody is tracked yet.")),
    el("p", { class: "foot" }, "Select a person to compare each selfie with their approved face, and to open the place in Maps."));
}

// ---- Employees ----

function tempPassword() {
  const a = "abcdefghjkmnpqrstuvwxyz23456789";
  const b = crypto.getRandomValues(new Uint8Array(12));
  return Array.from(b, x => a[x % a.length]).join("");
}

async function employeesView() {
  const data = await api("GET", "/v1/admin/users");
  const users = data.users;
  const sel = users.find(u => u.id === state.empSel);
  const rows = users.map(u => el("tr", { class: "person" + (u.id === state.empSel ? " sel" : ""), tabindex: "0",
    onclick: () => { state.empSel = u.id; state.empNew = false; render(); } },
    el("td", {}, el("div", { class: "who" }, el("span", { class: "avatar" }, initials(u.display_name)),
      el("span", { class: "nm" }, el("b", {}, u.display_name), el("small", {}, u.employee_code)))),
    el("td", {}, u.role === "ADMIN" ? "Admin" : "Inspector"),
    el("td", {}, u.status === "ACTIVE" ? (u.must_change_password ? el("span", { class: "chip PENDING" }, "Temporary password") : el("span", { class: "chip APPROVED" }, "Active"))
      : el("span", { class: "chip CANCELLED" }, "Disabled")),
    el("td", {}, u.track_attendance ? (u.attendance_from ? "Tracked from " + dayName(u.attendance_from) : "Tracked") : el("span", { class: "muted" }, "Not tracked")),
    el("td", {}, ago(u.last_seen_at))));
  const panel = state.empNew ? sidePanel(newEmployeeForm(), () => { state.empNew = false; render(); })
    : sel ? sidePanel(employeeEditor(sel), () => { state.empSel = null; render(); }) : null;
  return el("div", {},
    el("div", { class: "pagehead" },
      el("div", {}, el("h1", {}, "Employees"), el("p", { class: "lede" }, users.filter(u => u.status === "ACTIVE").length + " active. Select one to edit.")),
      el("button", { class: "btn", onclick: () => { state.empNew = true; state.empSel = null; render(); } }, "Add employee")),
    el("div", { class: panel ? "split" : "" },
      el("div", { class: "sheet scrollx" }, el("table", { class: "tbl" },
        el("thead", {}, el("tr", {}, ["Name", "Role", "Account", "Attendance", "Last seen"].map(h => el("th", {}, h)))), el("tbody", {}, rows))),
      panel));
}

function newEmployeeForm() {
  const code = el("input", { required: true, maxlength: "20", autocapitalize: "characters", placeholder: "e.g. RAVI" });
  const name = el("input", { required: true, maxlength: "100" });
  const role = el("select", {}, el("option", { value: "INSPECTOR" }, "Inspector"), el("option", { value: "ADMIN" }, "Admin"));
  const pw = el("input", { required: true, minlength: "10", value: tempPassword(), class: "mono" });
  const msg = el("p", { class: "err small" });
  return el("div", {}, el("h2", {}, "Add employee"),
    el("form", { class: "stack", onsubmit: async (ev) => {
      ev.preventDefault(); msg.textContent = "";
      try {
        await api("POST", "/v1/admin/users", { employee_code: code.value.trim(), display_name: name.value.trim(), role: role.value, password: pw.value });
        toast(name.value.trim() + " added. Give them the code and temporary password."); state.empNew = false; render();
      } catch (e) { msg.textContent = e.message; }
    } },
      el("label", { class: "f" }, "Employee code (they sign in with it)", code), el("label", { class: "f" }, "Full name", name),
      el("label", { class: "f" }, "Role", role),
      el("label", { class: "f" }, "Temporary password (they must change it at first sign-in)", pw),
      el("button", { class: "btn", type: "submit" }, "Add employee"), msg));
}

function employeeEditor(u) {
  const self = u.id === state.user.id;
  const patch = async (body, done) => {
    try { await api("PATCH", "/v1/admin/users/" + u.id, body); toast(done); render(); } catch (e) { toast(e.message); }
  };
  const name = el("input", { value: u.display_name, maxlength: "100" });
  const role = el("select", { disabled: self }, el("option", { value: "INSPECTOR", selected: u.role === "INSPECTOR" }, "Inspector"),
    el("option", { value: "ADMIN", selected: u.role === "ADMIN" }, "Admin"));
  const track = el("input", { type: "checkbox", checked: u.track_attendance });
  const from = el("input", { type: "date", value: u.attendance_from || "" });
  const pw = el("input", { value: tempPassword(), minlength: "10", class: "mono" });
  return el("div", {},
    el("h2", {}, u.display_name), el("p", { class: "muted small mt0" }, u.employee_code + " · last seen " + ago(u.last_seen_at)),
    el("form", { class: "stack", onsubmit: (ev) => { ev.preventDefault(); patch({ display_name: name.value, role: role.value }, "Saved."); } },
      el("label", { class: "f" }, "Name", name), el("label", { class: "f" }, "Role", role),
      self ? el("p", { class: "small muted m0" }, "You can't change your own role.") : null,
      el("button", { class: "btn ghost", type: "submit" }, "Save name and role")),
    el("div", { class: "subsection" }, el("h2", {}, "Attendance"),
      el("form", { class: "stack", onsubmit: (ev) => { ev.preventDefault(); patch({ track_attendance: track.checked, attendance_from: from.value }, "Attendance settings saved."); } },
        el("label", { class: "check" }, track, "Track this person's attendance"),
        el("label", { class: "f" }, "Counted from (joining date)", from),
        el("p", { class: "small muted m0" }, "Days before this date are never marked missed."),
        el("button", { class: "btn ghost", type: "submit" }, "Save attendance settings"))),
    el("div", { class: "subsection" }, el("h2", {}, "Password"),
      el("form", { class: "stack", onsubmit: (ev) => {
        ev.preventDefault();
        if (confirm("Reset " + u.display_name + "'s password? They're signed out on every phone and must use this temporary password next.")) {
          patch({ password: pw.value }, "Password reset. Give " + u.display_name + " the temporary password.");
        }
      } }, el("label", { class: "f" }, "New temporary password", pw), el("button", { class: "btn ghost", type: "submit" }, "Reset password"))),
    self ? null : el("div", { class: "subsection" },
      u.status === "ACTIVE"
        ? el("button", { class: "btn red", onclick: () => { if (confirm("Disable " + u.display_name + "? They're signed out at once and can't sign in. Their data stays.")) patch({ status: "DISABLED" }, u.display_name + " disabled."); } }, "Disable account")
        : el("button", { class: "btn", onclick: () => patch({ status: "ACTIVE" }, u.display_name + " enabled.") }, "Enable account")));
}

// ---- Phones and failed uploads ----

async function phonesView() {
  const data = await api("GET", "/v1/admin/devices");
  const devs = data.devices;
  const sel = devs.find(d => d.id === state.phoneSel);
  const rep = (d) => d.sync_report || {};
  const rows = devs.map(d => {
    const r = rep(d), problems = (r.problems || []).length, waiting = (r.pending_records || 0) + (r.pending_photos || 0);
    return el("tr", { class: "person" + (d.id === state.phoneSel ? " sel" : ""), tabindex: "0", onclick: () => { state.phoneSel = d.id; render(); } },
      el("td", {}, el("b", {}, d.user), el("div", { class: "small muted" }, d.employee_code)),
      el("td", {}, d.label || "Phone", el("div", { class: "small muted" }, [d.platform, d.app_version && "app " + d.app_version].filter(Boolean).join(", "))),
      el("td", {}, ago(d.last_seen_at)),
      el("td", {}, d.sync_report ? (waiting ? waiting + " waiting" : "All uploaded") : el("span", { class: "muted" }, "No report yet"),
        r.free_bytes != null ? el("div", { class: "small muted" }, bytes(r.free_bytes) + " free") : null),
      el("td", {}, d.revoked_at ? el("span", { class: "chip CANCELLED" }, "Blocked") : problems ? el("span", { class: "chip REJECTED" }, problems + (problems === 1 ? " failed item" : " failed items")) : null));
  });
  let panel = null;
  if (sel) {
    const r = rep(sel);
    const setBlocked = async (revoked) => {
      try { await api("PATCH", "/v1/admin/devices/" + sel.id, { revoked }); toast(revoked ? "Phone blocked." : "Phone unblocked."); render(); } catch (e) { toast(e.message); }
    };
    panel = sidePanel(el("div", {},
      el("h2", {}, sel.label || "Phone"), el("p", { class: "muted small mt0" }, sel.user + " · last seen " + ago(sel.last_seen_at)),
      el("dl", {},
        el("dt", {}, "Waiting to upload"), el("dd", {}, sel.sync_report ? (r.pending_records || 0) + " records, " + (r.pending_photos || 0) + " photos" : "No report yet"),
        el("dt", {}, "Free space"), el("dd", {}, bytes(r.free_bytes)),
        el("dt", {}, "Photos on phone"), el("dd", {}, sel.sync_report ? (r.local_originals || 0) + " (" + bytes(r.local_originals_bytes) + ")" : "—"),
        el("dt", {}, "Reported"), el("dd", {}, sel.sync_reported_at ? ago(sel.sync_reported_at) : "never")),
      el("h2", {}, "Failed items"),
      (r.problems || []).length ? el("ul", { class: "problems" }, r.problems.map(p => el("li", {},
        el("b", {}, p.type.toLowerCase() + " · " + p.op.toLowerCase().replaceAll("_", " ")), el("span", {}, p.message || p.code),
        el("small", { class: "muted" }, p.attempts + (p.attempts === 1 ? " attempt" : " attempts"))))) : el("p", { class: "muted" }, "Nothing has failed."),
      el("div", { class: "subsection" }, sel.revoked_at
        ? el("button", { class: "btn", onclick: () => setBlocked(false) }, "Unblock phone")
        : el("button", { class: "btn red", onclick: () => { if (confirm("Block this phone? It's signed out at once and can't sync until unblocked. Its data stays on the phone.")) setBlocked(true); } }, "Block phone"))),
      () => { state.phoneSel = null; render(); });
  }
  return el("div", {},
    el("div", { class: "pagehead" }, el("div", {}, el("h1", {}, "Phones"),
      el("p", { class: "lede" }, "What each phone still has to upload, and what keeps failing. Phones report after every sync."))),
    el("div", { class: panel ? "split" : "" },
      el("div", { class: "sheet scrollx" }, devs.length ? el("table", { class: "tbl" },
        el("thead", {}, el("tr", {}, ["Person", "Phone", "Last seen", "Uploads", ""].map(h => el("th", {}, h)))), el("tbody", {}, rows))
        : el("p", { class: "empty" }, "No phones have signed in yet.")),
      panel));
}

// ---- Backup: inspections on the server, monthly exports ----

let backupQuery = "";
async function backupView() {
  const [st, ins] = await Promise.all([api("GET", "/v1/admin/storage"), api("GET", "/v1/admin/inspections?q=" + encodeURIComponent(backupQuery))]);
  const figure = (v, l, cls) => el("div", { class: "fig " + (cls || "") }, el("b", {}, v), el("span", {}, l));
  const warn = st.unexported_bytes >= st.export_warn_bytes;
  const exportsList = st.exports.map(exportRow);
  const prepare = el("button", { class: "btn", onclick: async () => {
    prepare.disabled = true; prepare.textContent = "Preparing… (checks every file)";
    try {
      const r = await api("POST", "/v1/admin/exports");
      toast("Export ready: " + r.export.label + (r.problems && r.problems.length ? ". " + r.problems.length + " damaged files were left out; see Audit log." : ""));
    } catch (e) { toast(e.code === "NOTHING_TO_EXPORT" ? "Everything is already in a verified export." : e.message); }
    render();
  } }, "Prepare next export");
  const search = el("input", { type: "search", placeholder: "Search client or address", value: backupQuery, class: "w280" });
  let timer;
  search.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(() => { backupQuery = search.value.trim(); render(); }, 400); });
  const insRows = ins.inspections.map(i => {
    const missing = i.photos - i.photos_verified;
    return el("tr", {},
      el("td", {}, el("b", {}, i.client_name), el("div", { class: "small muted" }, i.property_address)),
      el("td", {}, dayName(i.inspection_date), el("div", { class: "small muted" }, i.inspector)),
      el("td", { class: "num" }, i.photos_verified + " / " + i.photos, missing ? el("div", { class: "small amber" }, missing + " not on server") : null),
      el("td", { class: "num" }, i.reports_verified + " / " + i.reports),
      el("td", { class: "num" }, i.photos_exported + (i.photos_archived ? el("div", { class: "small muted" }, i.photos_archived + " archived") : "")),
      el("td", {}, missing || i.reports_verified < i.reports ? el("span", { class: "chip PENDING" }, "Uploading") : el("span", { class: "chip APPROVED" }, "Backed up")));
  });
  return el("div", {},
    el("div", { class: "pagehead" }, el("div", {}, el("h1", {}, "Backup"),
      el("p", { class: "lede" }, "Download monthly exports to this computer, then remove old files from the server to free space."))),
    el("div", { class: "figures" },
      figure(bytes(st.data_free_bytes), "free on the server of " + bytes(st.data_total_bytes)),
      figure(bytes(st.objects_bytes), "photos and reports on the server"),
      figure(bytes(st.unexported_bytes), st.unexported_count + " files not in a verified export", warn ? "amber" : ""),
      figure(bytes(st.archived_bytes), "removed after export"),
      figure(st.last_export_at ? ago(st.last_export_at) : "never", "last verified export")),
    el("div", { class: "sheet" },
      el("div", { class: "row mb12" }, el("h2", { class: "m0" }, "Monthly exports"), el("span", { class: "spacer" }), prepare),
      el("ol", { class: "steps" },
        el("li", {}, "Prepare: the server bundles the oldest month not yet exported (up to about 1.5 GB). Each export holds only files not in an earlier checked export, so a month can have several exports; together they add up to the total on the server."),
        el("li", {}, "Download the zip and keep it somewhere safe (an external drive or cloud storage)."),
        el("li", {}, "Check the downloaded file: every photo is re-read here and compared with the server."),
        el("li", {}, "Remove from server: only files of inspections finalized at least " + st.archive_min_age_days + " days ago. Their records stay.")),
      exportsList.length ? el("div", { class: "exports" }, exportsList) : el("p", { class: "empty" }, "No exports yet.")),
    el("div", { class: "sheet mt16 scrollx" },
      el("div", { class: "row mb12" }, el("h2", { class: "m0" }, "Inspections on the server"), el("span", { class: "spacer" }), search),
      insRows.length ? el("table", { class: "tbl" },
        el("thead", {}, el("tr", {}, ["Client and property", "Inspected", "Photos", "Reports", "Exported", ""].map(h => el("th", { class: ["Photos", "Reports", "Exported"].includes(h) ? "num" : "" }, h)))),
        el("tbody", {}, insRows)) : el("p", { class: "empty" }, "No inspections found.")));
}

const EXPORT_STATUS = { CREATED: ["Not checked yet", "PENDING"], VERIFIED: ["Checked", "APPROVED"], ARCHIVED: ["Removed from server", "CANCELLED"] };

function exportRow(e) {
  const [label, cls] = EXPORT_STATUS[e.status] || [e.status, ""];
  const progress = el("span", { class: "small muted" });
  const download = el("button", { class: "btn small", disabled: !!e.archived_at, title: e.archived_at ? "Files were removed from the server: use your saved copy" : "", onclick: async () => {
    try {
      // A short-lived link the browser downloads by itself (resumable, never held in page memory).
      const r = await api("POST", "/v1/admin/exports/" + e.id + "/link");
      const a = el("a", { href: r.path, download: e.file_name || ("griha-export-" + e.id + ".zip") });
      document.body.append(a); a.click(); a.remove();
    } catch (err) { toast(err.message); }
  } }, "Download zip");
  const file = el("input", { type: "file", accept: ".zip", class: "hidden" });
  file.addEventListener("change", async () => {
    if (!file.files[0]) return;
    try {
      const res = await verifyExportZip(file.files[0], e, (done, total) => { progress.textContent = "Checking… " + Math.floor(done * 100 / total) + "%"; });
      progress.textContent = "";
      if (!res.ok) { toast(res.message); return; }
      await api("POST", "/v1/admin/exports/" + e.id + "/verified", { manifest_sha256: res.manifestSha, entries_verified: res.entries });
      toast("Export checked: all " + res.entries + " files match the server.");
      render();
    } catch (err) { progress.textContent = ""; toast(err.message); }
    file.value = "";
  });
  const check = el("button", { class: "btn small ghost", onclick: () => file.click() }, e.status === "CREATED" ? "Check downloaded file" : "Check again");
  const archive = el("button", { class: "btn small red", onclick: async () => {
    if (!confirm("Remove this export's old files from the server? Keep the zip safe: after this, those photos and PDFs exist only in your copy.")) return;
    try {
      const r = await api("POST", "/v1/admin/exports/" + e.id + "/archive");
      toast("Removed " + r.archived_now + " files (" + bytes(r.freed_bytes) + ")." + (r.kept_too_recent ? " " + r.kept_too_recent + " kept: not old enough yet." : ""));
      render();
    } catch (err) { toast(err.message); }
  } }, "Remove from server");
  return el("div", { class: "export" },
    el("div", { class: "grow" }, el("b", {}, e.label), el("div", { class: "small muted" },
      (e.entry_count - 2) + " photos and reports + list and data files · " + bytes(e.zip_bytes) + " · prepared " + when(e.created_at) + (e.verified_at ? " · checked " + when(e.verified_at) : ""))),
    el("span", { class: "chip " + cls }, label),
    el("div", { class: "row" }, progress, download, check, file, e.verified_at ? archive : null));
}

// Re-reads every entry of the downloaded zip (Store method, sizes in each local header) and compares it
// with manifest.csv and the server's manifest hash — the same check the phone does before "verified".
async function verifyExportZip(f, exp, onProgress) {
  const hex = (buf) => Array.from(new Uint8Array(buf), b => b.toString(16).padStart(2, "0")).join("");
  const entries = new Map();
  let manifest = null, off = 0;
  const dec = new TextDecoder();
  while (off + 30 <= f.size) {
    const h = new DataView(await f.slice(off, off + 30).arrayBuffer());
    if (h.getUint32(0, true) !== 0x04034b50) break; // central directory reached
    if (h.getUint16(8, true) !== 0) return { ok: false, message: "This zip isn't a Griha export (compressed entries)." };
    const size = h.getUint32(18, true), nameLen = h.getUint16(26, true), extraLen = h.getUint16(28, true);
    const name = dec.decode(await f.slice(off + 30, off + 30 + nameLen).arrayBuffer());
    const start = off + 30 + nameLen + extraLen;
    if (start + size > f.size) return { ok: false, message: "The file is incomplete. Download it again." };
    const data = await f.slice(start, start + size).arrayBuffer();
    if (entries.has(name)) return { ok: false, message: name + " appears twice in the zip." };
    entries.set(name, { sha: hex(await crypto.subtle.digest("SHA-256", data)), size });
    if (name === "manifest.csv") manifest = data;
    off = start + size;
    onProgress(off, f.size);
  }
  if (!manifest) return { ok: false, message: "manifest.csv is missing: is this the right file?" };
  const manifestSha = hex(await crypto.subtle.digest("SHA-256", manifest));
  if (manifestSha.toLowerCase() !== exp.manifest_sha256.toLowerCase()) return { ok: false, message: "This zip belongs to a different export, or it changed. Download it again." };
  const lines = dec.decode(manifest).split("\n").slice(1).filter(l => l.trim());
  const listed = new Set();
  for (const line of lines) {
    const [path, sha, size] = line.split(",");
    listed.add(path);
    const got = entries.get(path);
    if (!got) return { ok: false, message: path + " is missing from the zip." };
    if (got.sha.toLowerCase() !== (sha || "").toLowerCase() || got.size !== Number(size)) return { ok: false, message: path + " is damaged. Download it again." };
  }
  for (const name of entries.keys()) if (name !== "manifest.csv" && !listed.has(name)) return { ok: false, message: "Unexpected file in the zip: " + name };
  if (entries.size !== exp.entry_count) return { ok: false, message: "The zip has " + entries.size + " files, expected " + exp.entry_count + "." };
  return { ok: true, manifestSha, entries: entries.size };
}

// ---- Audit log ----

const AUDIT_LABEL = {
  LOGIN: "Signed in", LOGIN_FAILED: "Wrong password", PASSWORD_CHANGED: "Changed password", USER_CREATED: "Employee added", USER_UPDATED: "Employee changed",
  DEVICE_BLOCKED: "Phone blocked", DEVICE_UNBLOCKED: "Phone unblocked", REPORT_DOCX: "Word report downloaded",
  LEAVE_REQUESTED: "Leave requested", LEAVE_APPROVED: "Leave approved", LEAVE_REJECTED: "Leave rejected", LEAVE_CANCELLED: "Leave cancelled", LEAVE_RECORDED: "Leave recorded",
  HOLIDAY_SET: "Holiday set", HOLIDAY_REMOVED: "Holiday removed", ATTENDANCE_IN: "Checked in", ATTENDANCE_OUT: "Checked out",
  ATTENDANCE_EXPORT: "Attendance downloaded (Excel)", EXPORT_CREATED: "Export prepared", EXPORT_DOWNLOAD_LINK: "Export downloaded", EXPORT_VERIFIED: "Export checked", EXPORT_ARCHIVED: "Files removed after export",
  BACKUP_INTEGRITY_FAILED: "Damaged file on server", UPLOAD_CHECKSUM_MISMATCH: "Upload failed its check",
  PHOTO_BACKED_UP: "Photo backed up", REPORT_BACKED_UP: "Report backed up",
  FACE_ENROLLED: "Face set up", FACE_APPROVED: "Face approved", FACE_REJECTED: "Face rejected",
  COMP_OFF_GRANT: "Comp-off granted", COMP_OFF_REVOKE: "Comp-off taken back", COMP_OFF_CLEAR: "Comp-off reset",
  SETTINGS_CHANGED: "Settings changed", AI_KEY_SET: "AI key set", AI_KEY_REMOVED: "AI key removed",
  SELFIES_PURGED: "Old selfies deleted", LOCATIONS_PURGED: "Old locations deleted",
};
let auditState = { type: "", events: [], done: false };

async function auditView() {
  if (!auditState.events.length && !auditState.done) await loadAudit();
  const types = Object.entries(AUDIT_LABEL).sort((a, b) => a[1].localeCompare(b[1]));
  const filter = el("select", { onchange: async (e) => { auditState = { type: e.target.value, events: [], done: false }; render(); } },
    el("option", { value: "" }, "All events"), types.map(([k, l]) => el("option", { value: k, selected: k === auditState.type }, l)));
  const rows = auditState.events.map(a => el("tr", {},
    el("td", { class: "nowrap" }, when(a.created_at)),
    el("td", {}, AUDIT_LABEL[a.type] || a.type.toLowerCase().replaceAll("_", " ")),
    el("td", {}, a.user ? el("span", {}, a.user, el("span", { class: "small muted" }, " " + a.employee_code)) : el("span", { class: "muted" }, "System")),
    el("td", { class: "small muted" }, [a.device, a.from_device ? "reported by phone" : ""].filter(Boolean).join(", ")),
    el("td", { class: "small" }, [a.entity, a.details].filter(Boolean).join(" · "))));
  const more = auditState.done ? null : el("button", { class: "btn ghost", onclick: async () => { await loadAudit(); render(); } }, "Load older");
  return el("div", {},
    el("div", { class: "pagehead" }, el("div", {}, el("h1", {}, "Audit log"), el("p", { class: "lede" }, "Sign-ins, changes, exports and approvals, newest first.")), filter),
    el("div", { class: "sheet scrollx" }, rows.length ? el("table", { class: "tbl" },
      el("thead", {}, el("tr", {}, ["When", "What", "Who", "Where", "Details"].map(h => el("th", {}, h)))), el("tbody", {}, rows))
      : el("p", { class: "empty" }, "No events.")),
    more ? el("div", { class: "center mt12" }, more) : null);
}

async function loadAudit() {
  const before = auditState.events.length ? auditState.events[auditState.events.length - 1].id : 0;
  const r = await api("GET", "/v1/admin/audit?limit=100" + (before ? "&before=" + before : "") + (auditState.type ? "&type=" + auditState.type : ""));
  auditState.events.push(...r.events);
  if (r.events.length < 100) auditState.done = true;
}

boot();
