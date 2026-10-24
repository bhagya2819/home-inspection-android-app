#!/bin/sh
# Weekly Griha backup. Usage (in /root/apps/griha): sh backup.sh
#  1. pg_dump of the griha DB -> backups/db/griha-YYYYMMDD-HHMM.dump (checked with pg_restore --list, keeps 8).
#  2. Optional off-site copy: only if rclone/rclone.conf exists with a remote named "offsite"
#     (e.g. a dedicated backup Google account). Objects are immutable: copied with --ignore-existing, never deleted.
# Writes backups/last-backup.txt on success. Touches nothing outside this folder.
set -eu
cd "$(dirname "$0")"
RCLONE_IMAGE=rclone/rclone:1.68.2
KEEP_DUMPS=8

mkdir -p backups/db
exec 9>backups/.lock
flock -n 9 || { echo "backup already running"; exit 1; }

stamp=$(date -u +%Y%m%d-%H%M)
dump=backups/db/griha-$stamp.dump
docker exec griha-postgres pg_dump -U griha -d griha -Fc > "$dump.tmp"
# A dump that pg_restore can't list is not a backup.
docker exec -i griha-postgres pg_restore --list < "$dump.tmp" > /dev/null
mv "$dump.tmp" "$dump"
ls -1t backups/db/griha-*.dump | tail -n +$((KEEP_DUMPS + 1)) | xargs -r rm -f

offsite=skipped
if [ -f rclone/rclone.conf ]; then
  rc() { docker run --rm -v "$PWD/rclone:/config/rclone" -v "$PWD/data/objects:/objects:ro" \
    -v "$PWD/backups/db:/db:ro" "$RCLONE_IMAGE" "$@"; }
  rc copy /objects offsite:griha/objects --ignore-existing --exclude "tmp/**"
  rc copy /db offsite:griha/db --ignore-existing
  rc delete offsite:griha/db --min-age 90d
  offsite=ok
fi

echo "$(date -Is) ok dump=$(basename "$dump") size=$(stat -c%s "$dump") offsite=$offsite free=$(df -h --output=avail . | tail -1 | tr -d ' ')" \
  | tee backups/last-backup.txt
