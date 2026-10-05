package com.openlibrary.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The state of the backups, for whoever answers "is my data safe?".
 *
 * <p>Read-only on purpose. Making one is the backup container's job, and the
 * restore drill is {@code scripts/verify-restore.sh}: a button that shelled out to
 * pg_dump from the application would add a way to lose data, not to keep it.
 */
@RestController
@RequestMapping("/backup")
public class BackupStatusController {

    private final BackupStatusService backups;

    public BackupStatusController(BackupStatusService backups) {
        this.backups = backups;
    }

    @GetMapping("/status")
    public BackupDtos.Status status() {
        return backups.status();
    }
}