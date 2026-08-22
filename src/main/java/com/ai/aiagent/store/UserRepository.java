package com.ai.aiagent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
@Slf4j
public class UserRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public UserRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void upsert(String objectId, String upn, String displayName, String department,
                       String jobTitle, Collection<String> groupIds) {
        try {
            String groupsJson = mapper.writeValueAsString(List.copyOf(groupIds));
            jdbc.update("""
                    INSERT INTO rag_users (entra_object_id, upn, display_name, department,
                                           job_title, group_ids, groups_synced_at, last_seen_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?::jsonb, now(), now())
                    ON CONFLICT (entra_object_id) DO UPDATE SET
                        upn              = EXCLUDED.upn,
                        display_name     = COALESCE(EXCLUDED.display_name, rag_users.display_name),
                        department       = COALESCE(EXCLUDED.department, rag_users.department),
                        job_title        = COALESCE(EXCLUDED.job_title, rag_users.job_title),
                        group_ids        = EXCLUDED.group_ids,
                        groups_synced_at = now(),
                        last_seen_at     = now()
                    """, objectId, upn, displayName, department, jobTitle, groupsJson);
        } catch (Exception e) {
            log.warn("Could not upsert rag_users for {}: {}", upn, e.getMessage());
        }
    }

    public void touch(String objectId) {
        try {
            jdbc.update("UPDATE rag_users SET last_seen_at = now() WHERE entra_object_id = ?::uuid",
                    objectId);
        } catch (Exception e) {
            log.debug("Could not update last_seen_at: {}", e.getMessage());
        }
    }
}
