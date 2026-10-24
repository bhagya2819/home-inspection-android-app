# Home Inspection release notes

Newest first. Send the relevant section with each APK.

## 0.9.0: calendar, leave and room photos
- **Leave:** Profile → Leave. Request Casual, Sick or Comp-off leave, full or half day; it works offline and is sent
  when you're back online. Your admin's decision shows on Home and in Profile.
- **Your month** (same screen): each day shows worked, leave, half day, comp-off, missed check-in or check-out, Sunday or holiday.
  Checking in on a Sunday or holiday earns a comp-off automatically.
- **Rooms in your order:** on a new inspection, rooms are added in the order you tick them (nothing is pre-ticked).
- **Common photos per room:** each room has a Common photos card above its findings. These photos print under the
  room heading in the report and never count as an issue.
- Each finalized report is also kept on the server as an editable **Word** copy (web dashboard).
- Admins: Profile → Admin → attendance calendar, leave approvals, holidays, and per-employee attendance settings.
  The web dashboard (`https://api.homeinspection.example/web/`, same login) has reports (PDF/Word, any version),
  calendar, leave, attendance by day, employees, phones, backups/exports and the audit log.
- Install it over 0.8.x with **Update**. Never uninstall first.

## 0.8.1: report fixes
- **Finalize now asks first.** Tapping "Finalize as version N" opens a short confirmation; nothing is created until
  you tap **Finalize** in it. Stray taps near the bottom of the screen while scrolling can no longer make a version.
- **One tap makes one version.** If you leave the report while it is still finalizing and open it again, you now see
  "Finalizing…" instead of the button, and a report that hasn't changed is never finalized twice.
- Reports and photos from other phones load faster: a download you walk away from no longer holds up the next one.
- Home: check-in and location sharing are now compact pills at the top.
- Install it over 0.8.0 with **Update**. Never uninstall first.

## 0.8.0: attendance and live location
- **Check in / check out with your face** (top of Home). Set up your face once (Profile → Face check-in); your admin
  approves it, then check-in works even offline. Each check-in saves a selfie and your location. Not checking in never
  blocks your work; it is only a reminder.
- **Location sharing:** checking in shares your location with your admin, checking out stops it. You can also switch it
  on yourself from the card on Home. To turn it off during work, pick a reason (low battery, break, other); your admin
  sees that you turned it off and why. It stays off until you turn it on or check out.
- The first time, Home Inspection shows what is shared and for how long (90 days). Allow **notifications**: while sharing, a
  notification shows it and has a **Turn off** button.
- **Xiaomi / POCO / Redmi:** on the location card (or Profile → Location sharing on this phone), follow the 1-minute
  battery guide: allow background running, **Autostart on**, battery saver **No restrictions**. Without it the phone
  may stop sharing in the background.
- If the phone restarts or Home Inspection is updated while sharing, you get **"Tap to resume"**; opening Home Inspection resumes it.
- Admins: Profile → Admin → **Team locations** (alerts, list or map, each engineer's day with route and stops),
  **Attendance**, **Face set-ups** (approve faces). Alerts show when you open the app; there are no push notifications yet.
- Install it over the version you have with **Update**. Never uninstall first.

## 0.7.2: reliability
- Includes all 0.7.1 security fixes below.
- The app checks in with the Home Inspection server each time it opens.
- Install it over 0.7.0 or 0.7.1 with **Update**. Never uninstall first.

## 0.7.1: security fixes
- New passwords need at least 10 characters (your current password keeps working until you change it).
- AI requests: the client's name, phone number and address are removed from your notes before they leave the phone,
  and the server also removes any phone numbers or email addresses.
- While an account still has a temporary password, the phone waits for the new password before syncing (nothing is lost).
- Admins: blocking a phone signs it out; to stop a person, disable their account (they could sign in on another phone).
- Install it over 0.7.0 with **Update**. Never uninstall first.

## 0.7.0: AI writing help
- **Improve with AI** on a finding: write what you saw in the site note, in English, Telugu, Hindi or a mix. AI suggests the
  title, observation, implication, recommendation, severity and category, each shown under its field with ✓ and ✗.
  Nothing changes until you tap ✓. The site note itself never goes in the report.
- **Report summary:** on Review report, "Summary" → write it yourself or "Draft with AI". It is printed at the top of the
  report's summary page.
- **AI check before finalizing:** on Review report, "Check" lists findings with missing detail, a severity that doesn't match
  the text, possible duplicates or unclear wording. Tap one to open that finding. It never changes anything itself.
- AI needs the internet; everything else still works offline. Admins add the DeepSeek key once in Profile → Admin → AI assistant.
- Site notes are no longer printed in the report. A finding with only a site note shows "Copy site note into observation",
  and Finalize warns about findings without an observation.
- AI sees your notes and photo captions (not client details or photos): don't type client names into them.
- Camera: tap a thumbnail under the shutter to view that photo, then come back to the camera.
- Report preview: pinch to zoom right on the pages (no need to open a page first); zoomed pages can't get stuck off-screen.
- Install it over the version you have with **Update**. Never uninstall first.

## 0.6.1
- Light (cream) theme by default. Profile › Appearance: Light, Dark or Phone setting; the app remembers it.
- New inspection: rooms are fixed tiles; ticking one no longer makes the others jump around.
  Pressing Back on a half-filled form asks before discarding it.
- Finding screen redesigned: photos with an "Add photo" tile, colour-coded severity, notes grouped for the report,
  Open / Not applicable / Resolved as one switch.
- Home: pull down to sync now; filter chips show how many inspections each has; "Today"/"Yesterday" dates.
- Small touches: cards press in slightly, gentle vibration when ticking rooms or severity, smoother save/sync indicators.
- Install it over 0.6.0 with **Update**. Never uninstall first.

## 0.6.0: new look
- Cleaner design: plain white (or dark grey in dark mode) screens; green only marks the main button and "backed up".
- No more white flash when opening a screen.
- Bottom bar: Home, New inspection, Profile.
- Home shows your latest unfinished inspection at the top: tap **Continue** to carry on.
- Profile is now a simple menu: Backup, Phone storage, admin tools (admins only), Change password, Sign out.
  Sign out now asks first.
- Install it over 0.5.1 with **Update**. Never uninstall first.

## 0.5.1
- Earlier report versions made on another phone now open. Before, a download interrupted at the wrong moment could
  leave a broken partial file that showed "connect to the internet" forever.
- Photo zoom: pinch zooms where your fingers are; you can't drag the photo off-screen; while zoomed, dragging moves the
  photo instead of jumping to the next one. Double-tap zooms in at that spot or back out.
- Install it over 0.5.0 with **Update**. Never uninstall first.

## 0.5.0: first pilot release
- Inspections, rooms and findings with photos, all saved on the phone and working offline.
- PDF reports made on the phone, with numbered versions; share them on WhatsApp or by email.
- Sign in with your employee code; it works offline for 7 days at a time.
- Automatic backup: photos upload in the background and are checked byte for byte on the server.
- Backup & account screen: what is still waiting to upload, and phone storage.
- Optional automatic cleanup frees phone storage. It only removes originals 30 days after the report is finalized, once
  they are safely on the server *and* in an admin-verified export.
- Admins: employees, inspections' backup status, phones and their sync problems, audit log, server storage and exports.
- Fixes before the pilot: an open finding no longer overwrites a newer change made on another phone; photos downloaded
  from another phone appear without reopening the screen; signing out while offline also ends the session on the server
  once the phone is back online.
