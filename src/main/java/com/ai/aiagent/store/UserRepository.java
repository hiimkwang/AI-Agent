package com.ai.aiagent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Nguoi dung da dang nhap bang tai khoan cong ty.
 *
 * Bang nay KHONG dung de phan quyen luc chay - phan quyen lay tu Entra qua
 * {@link com.ai.aiagent.security.EntraScopeService}. No ton tai de:
 *   - audit: ai da dang nhap, lan cuoi khi nao, thuoc nhom nao luc do
 *   - giao dien quan tri: cap quyen cho "anh B" thi phai tra cuu duoc anh B
 *   - chuan doan: nguoi dung bao "toi khong xem duoc tai lieu X" thi xem duoc ngay
 *     luc dang nhap he thong thay ho thuoc nhom nao
 *
 * Coi day la BAN SAO DE DOC, khong phai nguon su that. Nguon su that la Entra.
 */
@Repository
@Slf4j
public class UserRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public UserRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Ghi nhan mot lan dang nhap / mot lan giai quyet nhom.
     *
     * Loi ghi DB o day KHONG duoc lam that bai viec dang nhap - day la du lieu phu tro.
     */
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
            log.warn("Khong ghi duoc rag_users cho {}: {}", upn, e.getMessage());
        }
    }

    /** Chi cap nhat moc thay lan cuoi - goi tren duong nong nen phai that re. */
    public void touch(String objectId) {
        try {
            jdbc.update("UPDATE rag_users SET last_seen_at = now() WHERE entra_object_id = ?::uuid",
                    objectId);
        } catch (Exception e) {
            log.debug("Khong cap nhat duoc last_seen_at: {}", e.getMessage());
        }
    }
}
