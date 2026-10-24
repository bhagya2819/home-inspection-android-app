#!/bin/sh
# Restores the newest DB dump into a throwaway Postgres (tmpfs, no network) and checks every verified photo/report
# object against the restored SHA-256. Touches no live data.
# Usage (in /root/apps/home-inspection): sh restore-test.sh [objects_dir]   (default data/objects; pass an off-site copy to test it)
set -eu
cd "$(dirname "$0")"
OBJECTS=${1:-data/objects}
dump=$(ls -1t backups/db/home-inspection-*.dump | head -1)
NAME=home-inspection-restore-test-$$
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$NAME" --network none --tmpfs /var/lib/postgresql/data -e POSTGRES_PASSWORD=test postgres:17-alpine >/dev/null
until docker exec "$NAME" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
docker exec "$NAME" createdb -U postgres home-inspection
docker exec -i "$NAME" pg_restore -U postgres -d home-inspection --no-owner --exit-on-error < "$dump"
q() { docker exec "$NAME" psql -U postgres -d home-inspection -Atc "$1"; }

echo "dump: $dump"
q "select 'inspections', count(*) from inspections union all select 'photos', count(*) from photos
   union all select 'reports', count(*) from reports union all select 'users', count(*) from users" | tr '|' ' '

checked=0; bad=0
for row in $(q "select object_key || '|' || lower(sha256) from photos where verified_at is not null
                union all select object_key || '|' || lower(content_hash) from reports where verified_at is not null"); do
  key=${row%|*}; want=${row#*|}
  if [ ! -f "$OBJECTS/$key" ]; then echo "MISSING $key"; bad=$((bad + 1))
  elif [ "$(sha256sum "$OBJECTS/$key" | cut -d' ' -f1)" != "$want" ]; then echo "MISMATCH $key"; bad=$((bad + 1))
  fi
  checked=$((checked + 1))
done
echo "objects checked: $checked, bad: $bad"
[ "$bad" -eq 0 ]
