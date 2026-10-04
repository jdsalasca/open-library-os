#!/bin/sh
# Rolling backups of the library database.
# ponytail: daily dumps with an age-based retention window. That window already
# covers several weeks of history, so a separate weekly tier would add nothing.
set -eu

INTERVAL="${BACKUP_INTERVAL:-86400}"
KEEP_DAYS="${KEEP_DAYS:-14}"
DEST=/backups

mkdir -p "$DEST"

dump_once() {
    stamp=$(date -u +%Y%m%dT%H%M%SZ)
    target="$DEST/openlibrary-$stamp.dump"

    if pg_dump --format=custom --file="$target"; then
        # A dump that cannot be listed cannot be restored; drop it and retry next tick.
        if pg_restore --list "$target" >/dev/null 2>&1; then
            echo "backup ok: $target"
            # Freshness marker: the compose healthcheck asserts on this, so "backups
            # are running" is observable from `docker compose ps` and not a guess.
            date -u +%s > "$DEST/.last-ok"
        else
            echo "backup corrupt, discarding: $target" >&2
            rm -f "$target"
        fi
    else
        rm -f "$target"
        echo "backup failed" >&2
    fi

    find "$DEST" -name 'openlibrary-*.dump' -type f -mtime "+$KEEP_DAYS" -delete
}

trap 'exit 0' TERM INT

# Run once at startup so a fresh deployment already has a restore point.
dump_once

while :; do
    sleep "$INTERVAL" &
    wait $!
    dump_once
done