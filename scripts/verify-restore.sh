#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# Verifica que los backups de esta instalación SE RESTAURAN de verdad.
#
# Un backup que nunca se ha restaurado no es un backup: es una suposición.
#
# Hace dos comprobaciones:
#   1. El último dump almacenado en /backups es legible por pg_restore.
#   2. Un pg_dump fresco se restaura en una base descartable y reproduce exactamente
#      las mismas filas que la base viva.
#
# (2) es la que importa: genera su propio dump porque comparar un dump antiguo con
# una base que ha seguido cambiando da desacuerdos falsos.
#
#   docker compose exec -T backup sh -s < scripts/verify-restore.sh
#
# Sale con 0 si todo cuadra. La base temporal se borra siempre, incluso al fallar.
# ─────────────────────────────────────────────────────────────────────────────
set -eu

LIVE_DB="${PGDATABASE:?falta PGDATABASE}"
SCRATCH_DB="${LIVE_DB}_restorecheck"
FRESH_DUMP=/tmp/verify-restore.dump

cleanup() {
    psql -q -d postgres -c "DROP DATABASE IF EXISTS ${SCRATCH_DB};" >/dev/null 2>&1 || true
    rm -f "$FRESH_DUMP"
}
trap cleanup EXIT

rc=0

# ── 1. El último backup almacenado tiene que ser legible ─────────────────────
latest=$(ls -1t /backups/openlibrary-*.dump 2>/dev/null | head -n 1 || true)
if [ -z "$latest" ]; then
    echo "AVISO: no hay ningún dump en /backups todavía (se crea al arrancar)"
else
    if pg_restore --list "$latest" >/dev/null 2>&1; then
        echo "backup almacenado: ok ($latest)"
    else
        echo "FALLO: el backup almacenado no se puede leer ($latest)" >&2
        rc=1
    fi
fi

# ── 2. Ida y vuelta completa: dump -> restore -> comparación ─────────────────
echo "generando dump de prueba…"
pg_dump --format=custom --no-owner --no-privileges --file="$FRESH_DUMP"

psql -q -d postgres -c "DROP DATABASE IF EXISTS ${SCRATCH_DB};" >/dev/null
psql -q -d postgres -c "CREATE DATABASE ${SCRATCH_DB};" >/dev/null

if ! pg_restore --no-owner --no-privileges -d "$SCRATCH_DB" "$FRESH_DUMP"; then
    echo "FALLO: pg_restore no pudo restaurar un dump recién creado" >&2
    rc=1
else
    echo "restore: ok — comparando filas (viva vs restaurada):"
    tables=$(psql -tA -d "$LIVE_DB" -c "
        select table_name from information_schema.tables
        where table_schema = 'public'
        order by table_name;")
    for table in $tables; do
        live=$(psql -tA -d "$LIVE_DB"     -c "select count(*) from \"$table\";")
        copy=$(psql -tA -d "$SCRATCH_DB" -c "select count(*) from \"$table\";")
        if [ "$live" = "$copy" ]; then
            printf '  %-24s %6s filas  ok\n' "$table" "$live"
        else
            printf '  %-24s %6s vs %-6s DESCUADRE\n' "$table" "$live" "$copy"
            rc=1
        fi
    done
fi

if [ "$rc" -eq 0 ]; then
    echo "RESULTADO: backup restaurable y consistente con la base viva"
else
    echo "RESULTADO: FALLO — el backup no sirve tal cual"
fi
exit "$rc"
