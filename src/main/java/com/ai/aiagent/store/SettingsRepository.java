package com.ai.aiagent.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Luu cau hinh doi luc runtime.
 *
 * Truoc day cac tham so retrieval bind bang {@code @Value} vao field cua service,
 * nghia la chi doc mot lan luc khoi tao bean -> muon thu {@code top-k} khac phai
 * restart, nen tren thuc te khong ai thu. Gio gia tri sua qua Settings API duoc ghi
 * o day va ap lai vao {@code RagProperties} moi lan khoi dong.
 */
@Repository
public class SettingsRepository {

    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> loadAll() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM rag_settings", rs -> {
            out.put(rs.getString("setting_key"), rs.getString("setting_value"));
        });
        return out;
    }

    public void put(String key, String value, String updatedBy) {
        jdbc.update("""
                INSERT INTO rag_settings (setting_key, setting_value, updated_by, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (setting_key) DO UPDATE SET
                    setting_value = EXCLUDED.setting_value,
                    updated_by    = EXCLUDED.updated_by,
                    updated_at    = now()
                """, key, value, updatedBy);
    }

    public void remove(String key) {
        jdbc.update("DELETE FROM rag_settings WHERE setting_key = ?", key);
    }

    public int clear() {
        return jdbc.update("DELETE FROM rag_settings");
    }
}
