# The Griha release key

**What it is:** every APK is signed with a private key. Android installs an *update* only if it is signed with exactly
the same key as the app already on the phone. That stops anyone from pushing a fake "Griha update" to inspectors, and it
means **all future updates need this one key**. Lose it, and every phone has to uninstall and reinstall, losing
anything not yet uploaded.

**Where it lives:** outside the repo, on the owner's Mac. The build reads it from `~/.griha/release-signing.properties`
(`app/build.gradle.kts`); without that file, release builds are debug-signed and `tools/release.sh` refuses them.

## Create it (once)
```bash
mkdir -p ~/.griha && "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair -v \
  -keystore ~/.griha/griha-release.jks -alias griha -keyalg RSA -keysize 4096 -validity 10000
```
Then create `~/.griha/release-signing.properties` (and `chmod 600` it):
```
storeFile=/Users/<you>/.griha/griha-release.jks
storePassword=<the password you chose>
keyAlias=griha
keyPassword=<the password you chose>
```

## Back it up (now, and keep it backed up)
- Copy `griha-release.jks` **and** the password to two places away from this Mac: a password manager entry (attach the
  file) and an encrypted USB stick kept in the office.
- Never commit it, email it, or send it on WhatsApp. Anyone with the file *and* password can sign "Griha updates".
- Validity: 10,000 days (~27 years).

## Release
`tools/release.sh` builds `dist/Griha-<version>.apk` and prints the APK and certificate SHA-256. Bump `versionCode` and
`versionName` in `app/build.gradle.kts` before each release.
