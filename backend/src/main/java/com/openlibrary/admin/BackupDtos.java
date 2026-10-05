package com.openlibrary.admin;

import java.time.Instant;
import java.util.List;

/** What the backup container has been doing, in words a person can act on. */
public final class BackupDtos {

    private BackupDtos() {
    }

    /** One dump on disk. */
    public record Dump(String name, long bytes) {
    }

    /**
     * @param healthy       whether there is a recent, known-good restore point
     * @param message       plain wording, already decided for the reader
     * @param lastRun       epoch seconds of the last validated dump, if any
     * @param hoursSinceLast age of that dump in hours, 0 when there is none
     * @param dumps         how many are kept in the volume
     * @param newest        the one that would be restored first
     */
    public record Status(
            boolean healthy,
            String message,
            Long lastRun,
            long hoursSinceLast,
            int dumps,
            Dump newest) {

        public List<Dump> all() {
            return newest == null ? List.of() : List.of(newest);
        }

        public Instant ranAt() {
            return lastRun == null ? null : Instant.ofEpochSecond(lastRun);
        }
    }
}
