#!/bin/bash
# Pulls the app DB from a connected debug-build phone and checks every photo row against its
# file on the device: SHA-256 and size must match, and the photo is either still queued (exactly one
# UPLOAD_ORIGINAL outbox entry) or server-verified (backup_checksum equals its SHA-256, no entry left).
# Read-only: copies the DB to a temp dir; touches nothing on the phone.
# Usage: tools/verify-device.sh   (needs adb + sqlite3; debug build for run-as)
A=${ADB:-~/Library/Android/sdk/platform-tools/adb}; P=com.homeinspection.app
S=${TMPDIR:-/tmp}/home-inspection-verify && rm -rf "$S" && mkdir -p "$S"
for f in home-inspection.db home-inspection.db-wal home-inspection.db-shm; do $A exec-out run-as $P cat databases/$f > "$S/$f" 2>/dev/null; done
Q="sqlite3 -readonly $S/home-inspection.db"
echo "== counts"; $Q "select 'inspections',count(*) from inspections union all select 'rooms',count(*) from rooms union all select 'findings',count(*) from findings union all select 'photos',count(*) from photos union all select 'outbox',count(*) from sync_outbox union all select 'audit',count(*) from audit_events;"
echo "== photos (file check)"; ok=0; bad=0
while IFS='|' read -r id path sha size w h; do
  dev=$($A shell -n run-as $P sha256sum files/$path 2>/dev/null | cut -d' ' -f1)
  dsz=$($A shell -n run-as $P stat -c %s files/$path 2>/dev/null | tr -d '\r')
  q=$($Q "select count(*) from sync_outbox where entity_id='$id' and operation='UPLOAD_ORIGINAL';")
  b=$($Q "select count(*) from photos where id='$id' and backup_checksum = sha256 and backup_verified_at is not null;")
  if [ "$q" = "1" ]; then st=queued; elif [ "$q" = "0" ] && [ "$b" = "1" ]; then st=backed-up; else st=bad; fi
  if [ "$dev" = "$sha" ] && [ "$dsz" = "$size" ] && [ "$st" != bad ]; then ok=$((ok+1)); echo "OK   ${id:0:8} ${w}x${h} $((size/1024))KB $st"; else bad=$((bad+1)); echo "FAIL ${id:0:8} db=$sha dev=$dev size=$size/$dsz queued=$q backed_up=$b"; fi
done < <($Q "select id,local_original_path,sha256,file_size_bytes,width,height from photos;")
echo "photos ok=$ok bad=$bad"
echo "== leftover tmp / recovered"; $A shell run-as $P ls -R files/photos/tmp files/photos/recovered 2>&1 | tr -d '\r'
echo "== audit"; $Q "select type,details from audit_events;"
echo "(DB copy kept at $S for ad-hoc sqlite3 queries)"
