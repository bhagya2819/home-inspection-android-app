# Installing and updating the Home Inspection app

For Home Inspection inspectors and admins. The app is not on the Play Store; your admin sends you the file.
You need: an Android phone (Android 8 or newer), your **employee code** and the **temporary password** your admin gave you.

## First install

1. **Get the file.** Your admin sends `HomeInspection-<version>.apk` (for example on WhatsApp). Only install a Home Inspection file
   that comes directly from your admin. Never install one forwarded by someone else.
2. **Open it.** Tap the file in WhatsApp (or in *Files › Downloads*).
3. **Allow the install (first time only).** Android says *"For your security, your phone is not allowed to install
   unknown apps from this source"*. Tap **Settings**, switch on **Allow from this source**, then go back.
   - This permission is for the app you opened the file from (WhatsApp or Files), not for Home Inspection.
   - Xiaomi / POCO / Redmi: you may also see *"Install via USB"* or a security scan. Tap **Install** / **Continue**.
     If MIUI asks you to sign in to a Mi account, tap **Cancel** and install from the Files app instead.
   - Samsung: *Settings › Apps › Special access › Install unknown apps › (WhatsApp/My Files) › Allow*.
4. Tap **Install**, then **Open**.
5. **Sign in** with your employee code and temporary password. The first sign-in needs the internet. You'll then choose
   your own password. Keep it private; admins never ask for it.
6. **Allow the camera** when asked.
7. **Attendance and location (0.8.0 and later):** set up your face once (Profile → Face check-in) and wait for your
   admin's approval. At your first check-in, read the location notice, tap **I understand**, allow **location**
   (precise) and **notifications**. On Xiaomi / POCO / Redmi phones, do the 1-minute battery guide on the location card.

After the first sign-in the app works **without internet** for up to 7 days at a time. Photos and notes are saved on the
phone and upload by themselves when there is a connection.

You can switch "Allow from this source" off again after installing. You'll need to switch it back on for the next update.

## Updating

Your admin sends a newer `HomeInspection-<version>.apk`. Open it the same way and tap **Update**.

- **Never uninstall Home Inspection to update it.** Uninstalling deletes everything on the phone that hasn't uploaded yet.
  An update keeps all your inspections and photos.
- If Android says *"App not installed"* or *"package conflicts with an existing package"*, **stop and tell your admin**.
  Don't uninstall. It means the file isn't a genuine Home Inspection update.
- Before updating, open **Backup & account** and check it says **All backed up**, if you can. That way nothing is
  at risk even in the worst case.

## Checking the file (admins)

Every release comes with two values from `tools/release.sh`:
- **APK SHA-256**: the fingerprint of the file. To check a downloaded copy on a Mac:
  `shasum -a 256 HomeInspection-<version>.apk`
- **Signing certificate SHA-256**: identical for every Home Inspection release, forever. Android itself refuses an update
  that is signed differently, so an inspector's phone already rejects a fake *update*. Only the first install relies on
  the file coming from you.

Send the APK only from the admin's own account, together with the release notes (`docs/release-notes.md`).

## Something's wrong?

| You see | Do this |
|---|---|
| "Can't reach the Home Inspection server" at sign-in | Connect to the internet; the first sign-in and any sign-in after 7 days offline need it. |
| "Your session has ended" | Sign in again. Your work on the phone is safe. Your admin may have reset your password. |
| Photos say "Waiting to upload" | Normal when offline. Open **Backup & account › Sync now** when you have a connection. |
| "Cleared from phone · backed up" on a photo | The original was removed from the phone after its retention period; it is kept on the server or in the admin's export. |
| Phone storage almost full | Make sure everything is backed up; your admin can explain the automatic cleanup (Backup & account › Phone storage). |
