package com.openlibrary.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * What the backup container has been doing, read from the volume it writes to.
 *
 * <p>The claim this project makes is that your data is yours and survives. The
 * operator should not have to run {@code docker compose logs backup} to find out
 * whether that is still true: the marker file is already there (the compose
 * healthcheck depends on it), so the app only has to read it.
 *
 * <p>The directory is mounted read-only. This never writes a backup, never
 * deletes one, and never shells out to pg_dump: it reports what is on disk.
 */
@Service
public class BackupStatusService {

    private static final Logger log = LoggerFactory.getLogger(BackupStatusService.class);
    private static final String MARKER = ".last-ok";
    private static final String PREFIX = "openlibrary-";
    private static final String SUFFIX = ".dump";

    private final Path dir;
    private final Duration staleAfter;

    public BackupStatusService(
            @Value("${openlibrary.backup.dir:/backups}") String dir,
            @Value("${openlibrary.backup.stale-hours:48}") long staleHours) {
        this.dir = Path.of(dir);
        this.staleAfter = Duration.ofHours(staleHours);
    }

    public BackupDtos.Status status() {
        if (!Files.isDirectory(dir)) {
            return unhealthy("No hay ningun volumen de respaldos montado.", 0, 0, null);
        }

        var dumps = dumps();
        var newest = dumps.isEmpty() ? null : dumps.get(0);
        Long lastRun = markerEpoch();

        if (lastRun == null) {
            return unhealthy("No se ha hecho ningun respaldo todavia.", 0, dumps.size(), newest);
        }
        if (newest == null) {
            return unhealthy("El ultimo respaldo guardado ya no esta.", hoursSince(lastRun),
                    dumps.size(), null);
        }

        long hours = hoursSince(lastRun);
        if (hours > staleAfter.toHours()) {
            return unhealthy("El ultimo respaldo es de hace " + hours + " horas.", hours,
                    dumps.size(), newest);
        }
        return new BackupDtos.Status(true,
                "Respaldos al dia: " + dumps.size() + " guardados, el ultimo hace "
                        + hours + " h.",
                lastRun, hours, dumps.size(), newest);
    }

    private BackupDtos.Status unhealthy(String message, long hours, int count,
            BackupDtos.Dump newest) {
        return new BackupDtos.Status(false, message, markerEpoch(), hours, count, newest);
    }

    /** Newest first, which is the order the caller wants to display them in. */
    private List<BackupDtos.Dump> dumps() {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
                    })
                    .filter(path -> Files.isRegularFile(path))
                    .sorted(Comparator.comparing((Path path) -> nameOf(path)).reversed())
                    .map(this::toDump)
                    .toList();
        } catch (IOException e) {
            log.warn("no se pudo leer el volumen de respaldos: {}", e.toString());
            return List.of();
        }
    }

    private BackupDtos.Dump toDump(Path path) {
        try {
            return new BackupDtos.Dump(nameOf(path), Files.size(path));
        } catch (IOException e) {
            return new BackupDtos.Dump(nameOf(path), 0);
        }
    }

    private String nameOf(Path path) {
        return path.getFileName().toString();
    }

    /** The epoch the backup script writes once a dump has passed its checks. */
    private Long markerEpoch() {
        try {
            Path marker = dir.resolve(MARKER);
            if (!Files.isRegularFile(marker)) {
                return null;
            }
            String text = Files.readString(marker).trim();
            return text.isEmpty() ? null : Long.parseLong(text);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private long hoursSince(Long epoch) {
        if (epoch == null) {
            return 0;
        }
        return Duration.between(Instant.ofEpochSecond(epoch), Instant.now()).toHours();
    }

    public record Dto(Map<String, Object> view) {
    }
}