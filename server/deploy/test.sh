#!/bin/sh
# Runs the Go tests on the server against a throwaway Postgres (tmpfs, removed afterwards).
# Usage (in /root/apps/griha): sh test.sh
set -eu
NET=griha-test-$$
docker network create "$NET" >/dev/null
cleanup() { docker rm -f "$NET-db" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --name "$NET-db" --network "$NET" --tmpfs /var/lib/postgresql/data -e POSTGRES_PASSWORD=test postgres:17-alpine >/dev/null
until docker exec "$NET-db" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
docker run --rm --network "$NET" -v "$PWD/src:/src" -v griha-gomod:/go/pkg/mod -w /src \
  -e TEST_DATABASE_URL="postgres://postgres:test@$NET-db:5432/postgres?sslmode=disable" \
  golang:1.26-alpine sh -c 'go mod tidy && go vet ./... && go test -v -count=1 ./...'
