#!/bin/sh
# Restore drill: proves the dumps in the `backups` volume really restore, and that
# the restored rows match the live database.
#
#   sh scripts/verify-restore.sh      # from the repo root, stack already up
#
# A backup nobody ever restored is a rumour, not a backup. The `backup` service
# only proves `pg_restore --list` succeeds, and an archive of an EMPTY database
# sails through that check too -- so this script is the one that compares rows.
#
# Newest dump in the volume -> scratch database inside the db container (the live
# one is never touched) -> pg_restore -> row counts vs. the live db -> two
# integrity queries -> verdict.
#
# Exit 0 only if every count matches and both integrity queries are clean.
# The scratch database is dropped on every exit, failure included (trap).
# ─────────────────────────────────────────────────────────────────────────────
set -eu

# Git Bash rewrites any /... argument into a Windows path before handing it to
# docker.exe, which turns "/backups" into "C:/Program Files/Git/backups". Every
# slash path in here is a path INSIDE a container, so keep it verbatim. No-op
# everywhere else.
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV

# Everything this script PRINTS is ASCII on purpose. Accented output captured from
# Git Bash into a file lands as mojibake (see docs/evidence/round-0), and a log
# nobody can read is not evidence. Accents are fine in these comments.

DC_SCRATCH=openlibrary_restorecheck

# Tables worth comparing. A library that has not installed the inventory or loans
# migrations has no copies/loans yet; information_schema decides, not a guess.
CANDIDATES="users books copies locations loans reservations app_config flyway_schema_history"

# Copies marked lent must have an open loan, and a copy cannot point at a shelf
# that is not there. Both hold in the live db, so both must hold after a restore.
SQL_LOST_LOAN="select count(*) from public.copies c where c.status = 'PRESTADO'
                 and not exists (select 1 from public.loans l
                                 where l.copy_id = c.id and l.returned_at is null);"
SQL_ORPHAN_LOCATION="select count(*) from public.copies c where c.location_id is not null
                 and not exists (select 1 from public.locations l where l.id = c.location_id);"

dc() { docker compose "$@"; }

DB_USER=$(dc exec -T db printenv POSTGRES_USER)
DB_LIVE=$(dc exec -T db printenv POSTGRES_DB)

# SQL runs inside the db container as the database owner. Deliberately not piped
# into a filter: under `set -e` a broken query has to abort the drill, not return
# an empty value that would compare equal to another empty and print a false ok.
q() { dc exec -T db psql -U "$DB_USER" -X -q -tA -d "$1" -c "$2"; }
rows() { q "$1" "select count(*) from public.\"$2\";"; }

# -tA emits one row PER LINE, so this list has to be folded into one line before it
# can be matched with `case " $list " in *" $t "*)`.
public_tables() { q "$1" "select table_name from information_schema.tables where table_schema = 'public' order by table_name;" | tr '\n' ' '; }

cleanup() {
    dc exec -T db psql -U "$DB_USER" -X -q -d postgres -c "DROP DATABASE IF EXISTS $DC_SCRATCH;" >/dev/null 2>&1 || true
}
trap cleanup EXIT

rc=0

# ── 1. Newest dump stored in the backups volume ────────────────────────────────
DUMP=$(dc exec -T backup ls -1t /backups | grep '\.dump$' | head -n 1 || true)
if [ -z "$DUMP" ]; then
    echo "VEREDICTO: FALLO - no hay ningun dump en el volumen backups" >&2
    exit 1
fi
echo "dump elegido: $DUMP ($(dc exec -T backup stat -c '%s' "/backups/$DUMP") bytes)"

# ── 2. Scratch database inside the db container, never the live one ───────────
q postgres "DROP DATABASE IF EXISTS $DC_SCRATCH;" >/dev/null 2>&1
q postgres "CREATE DATABASE $DC_SCRATCH;" >/dev/null
echo "base temporal: $DC_SCRATCH (la viva $DB_LIVE no se toca)"

# ── 3. Restore. The dump file stays on the volume, so the restore runs in the
#       backup container and points at the db service over the compose network.
# ──────────────────────────────────────────────────────────────────────────────
if dc exec -T backup pg_restore -h db -U "$DB_USER" --no-owner --no-privileges \
        -d "$DC_SCRATCH" "/backups/$DUMP"; then
    echo "pg_restore: ok"
else
    echo "pg_restore: FALLO (pg_restore ha fallado, ver salida arriba)" >&2
    rc=1
fi

restored_tables=$(public_tables "$DC_SCRATCH")
live_tables=$(public_tables "$DB_LIVE")

# Without this guard a failed table lookup yields an empty list, every candidate
# gets skipped as "not in the live db" and the drill passes having compared
# nothing at all. A drill that cannot see the schema must not report success.
if [ -z "$live_tables" ]; then
    echo "ABORTO: no se pudo leer information_schema de $DB_LIVE; nada que comparar" >&2
    exit 1
fi

# ── 4+5. Counts, live vs restored ─────────────────────────────────────────────
echo
printf '  %-24s %9s %10s  %s\n' tabla viva restaurada estado
for t in $CANDIDATES; do
    case " $live_tables " in
    *" $t "*) ;;
    *)
        printf '  %-24s %9s %10s  (no existe en la base viva, se omite)\n' "$t" - -
        continue
        ;;
    esac
    live_n=$(rows "$DB_LIVE" "$t")
    case " $restored_tables " in
    *" $t "*)
        copy_n=$(rows "$DC_SCRATCH" "$t")
        if [ "$live_n" = "$copy_n" ]; then
            state=ok
        else
            state=DESCUADRE
            rc=1
        fi
        ;;
    *)
        copy_n=ausente
        state='FALTA LA TABLA'
        rc=1
        ;;
    esac
    printf '  %-24s %9s %10s  %s\n' "$t" "$live_n" "$copy_n" "$state"
done
echo "  ---"
echo "  tablas public: viva=$(echo $live_tables | wc -w | tr -d ' ')  restaurada=$(echo $restored_tables | wc -w | tr -d ' ')"

# ── 6. Logical integrity of the restored data ─────────────────────────────────
echo
have() { case " $restored_tables " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

integrity() {
    # $1 label, $2 tables the query needs (space separated), $3 SQL returning a violation count
    for need in $2; do
        if ! have "$need"; then
            printf '  %-46s omitido (falta %s en la restaurada)\n' "$1" "$need"
            return
        fi
    done
    n=$(q "$DC_SCRATCH" "$3")
    if [ "$n" = "0" ]; then
        printf '  %-46s ok (0)\n' "$1"
    else
        printf '  %-46s FALLO (%s)\n' "$1" "$n"
        rc=1
    fi
}
integrity 'copias PRESTADO sin prestamo abierto' 'copies loans' "$SQL_LOST_LOAN"
integrity 'copies.location_id huerfano' 'copies locations' "$SQL_ORPHAN_LOCATION"

# ── 7. Verdict ────────────────────────────────────────────────────────────────
echo
if [ "$rc" -eq 0 ]; then
    echo "VEREDICTO: OK - $DUMP se restaura y sus datos cuadran con $DB_LIVE"
else
    echo "VEREDICTO: FALLO - $DUMP no sirve tal cual"
fi
exit "$rc"