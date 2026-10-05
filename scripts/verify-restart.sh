#!/usr/bin/env bash
# Proves the thing the round 7 notes claimed: that logging in survives a
# restart of the stack. Sessions live in Postgres, not in memory, so this must
# hold. If it ever stops holding, every librarian is logged out by a deploy.
#
# Usage: sh scripts/verify-restart.sh [base-url]
set -eu

BASE="${1:-${BASE_URL:-http://127.0.0.1:8090}}"
EMAIL="${ADMIN_EMAIL:-admin@local}"
PASSWORD="${ADMIN_PASSWORD:-NuevaClave2026}"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

login() {
  curl -sS -c "$JAR" -o /dev/null "$BASE/api/auth/csrf"
  csrf="$(awk '/XSRF-TOKEN/ {print $7}' "$JAR")"
  curl -sS -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' \
    -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
}

me() { curl -sS -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/api/auth/me"; }

fail() { echo "FALLO: $1" >&2; exit 1; }

echo "1. iniciando sesion en $BASE"
[ "$(login)" = "200" ] || fail "no se pudo iniciar sesion como $EMAIL"
[ "$(me)" = "200" ] || fail "la sesion no valida nada recien creada"

echo "2. reiniciando backend y frontend"
docker compose restart backend frontend >/dev/null
# The stack has a real start-up time; poll instead of sleeping a fixed guess.
for _ in $(seq 1 60); do
  # While the backend boots, curl has nothing to say. Silence it and poll.
  if [ "$(me 2>/dev/null)" = "200" ]; then break; fi
  sleep 2
done

echo "3. la misma cookie, despues del reinicio"
[ "$(me)" = "200" ] || fail "la sesion murio con el contenedor (la cookie ya no vale)"

echo "4. y sigue funcionando de verdad"
code="$(curl -sS -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/api/catalog/books?size=1")"
[ "$code" = "200" ] || fail "la cookie sobrevive pero el backend no responde ($code)"

echo "OK la sesion sobrevive al reinicio: vive en Postgres, no en la memoria"
