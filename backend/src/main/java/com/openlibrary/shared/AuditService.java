package com.openlibrary.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Append-only trail of business mutations. Lives in Postgres so it survives
 * restarts and can be exported with the rest of the data.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(Long userId, String action, String entity, Object entityId, Map<String, ?> details) {
        jdbc.update(
                "insert into audit_log (user_id, action, entity, entity_id, details) values (?,?,?,?,?::jsonb)",
                userId, action, entity,
                entityId == null ? null : entityId.toString(),
                details == null || details.isEmpty() ? null : toJson(details));
    }

    private String toJson(Map<String, ?> details) {
        return json.writeValueAsString(details);
    }
}