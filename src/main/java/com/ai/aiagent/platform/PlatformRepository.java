package com.ai.aiagent.platform;

import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.platform.PlatformModels.ChannelBinding;
import com.ai.aiagent.platform.PlatformModels.CollectionDef;
import com.ai.aiagent.platform.PlatformModels.Grant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Doc/ghi cau hinh nen tang: collection, bot, ACL, doi tuong su dung, rang buoc kenh.
 *
 * Cac bang nay rat nho (hang chuc dong) nhung duoc doc o MOI request, nen
 * {@link PlatformService} giu mot ban chup trong bo nho; repository nay chi lam viec
 * voi DB.
 */
@Repository
@Slf4j
public class PlatformRepository {

    private final JdbcTemplate jdbc;

    public PlatformRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ============================================================ Collection

    public List<CollectionDef> collections() {
        Map<Long, Set<String>> acl = aclByCollection();
        Map<String, Long> counts = documentCountBySlug();

        return jdbc.query("""
                SELECT id, slug, name, description, channel_allowed, status
                  FROM rag_collections
                 ORDER BY name
                """, (rs, i) -> {
            long id = rs.getLong("id");
            String slug = rs.getString("slug");
            return new CollectionDef(
                    id, slug, rs.getString("name"), rs.getString("description"),
                    rs.getBoolean("channel_allowed"), rs.getString("status"),
                    acl.getOrDefault(id, Set.of()),
                    counts.getOrDefault(slug, 0L));
        });
    }

    private Map<Long, Set<String>> aclByCollection() {
        Map<Long, Set<String>> out = new LinkedHashMap<>();
        jdbc.query("SELECT collection_id, entra_group_id FROM rag_collection_acl", rs -> {
            out.computeIfAbsent(rs.getLong("collection_id"), k -> new LinkedHashSet<>())
                    .add(rs.getString("entra_group_id").toLowerCase());
        });
        return out;
    }

    /** So tai lieu theo category - de giao dien canh bao collection rong. */
    private Map<String, Long> documentCountBySlug() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT category, count(*) AS n FROM rag_documents
                 WHERE category IS NOT NULL GROUP BY category
                """, rs -> {
            out.put(rs.getString("category"), rs.getLong("n"));
        });
        return out;
    }

    public long createCollection(String slug, String name, String description,
                                 boolean channelAllowed, String createdBy) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // Postgres tra ve MOI cot voi RETURN_GENERATED_KEYS => KeyHolder.getKey() no.
            // Phai chi dinh ro cot "id" - cung cach lam voi ChunkRepository.
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO rag_collections (slug, name, description, channel_allowed, created_by)
                    VALUES (?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, slug);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setBoolean(4, channelAllowed);
            ps.setString(5, createdBy);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public int updateCollection(long id, String name, String description, boolean channelAllowed,
                                String status) {
        return jdbc.update("""
                UPDATE rag_collections
                   SET name = ?, description = ?, channel_allowed = ?, status = ?, updated_at = now()
                 WHERE id = ?
                """, name, description, channelAllowed, status, id);
    }

    /**
     * Xoa cau hinh collection. KHONG xoa tai lieu: tai lieu la du lieu, cau hinh la
     * chinh sach. Xoa nham chinh sach thi khai bao lai; xoa nham tai lieu thi phai nap lai.
     */
    public int deleteCollection(long id) {
        return jdbc.update("DELETE FROM rag_collections WHERE id = ?", id);
    }

    public void setCollectionAcl(long collectionId, List<String> groupIds,
                                 List<String> groupNames, String grantedBy) {
        jdbc.update("DELETE FROM rag_collection_acl WHERE collection_id = ?", collectionId);
        for (int i = 0; i < groupIds.size(); i++) {
            String id = groupIds.get(i);
            if (id == null || id.isBlank()) continue;
            String name = groupNames != null && i < groupNames.size() ? groupNames.get(i) : null;
            jdbc.update("""
                    INSERT INTO rag_collection_acl (collection_id, entra_group_id, group_name, granted_by)
                    VALUES (?, ?::uuid, ?, ?)
                    ON CONFLICT (collection_id, entra_group_id) DO UPDATE SET group_name = EXCLUDED.group_name
                    """, collectionId, id.strip(), name, grantedBy);
        }
    }

    // ============================================================ Bot

    public List<BotDef> bots() {
        Map<Long, Set<String>> collections = botCollections();
        Map<Long, Set<String>> audienceGroups = audience("GROUP");
        Map<Long, Set<String>> audienceUsers = audience("USER");

        return jdbc.query("""
                SELECT id, slug, display_name, description, teams_app_id, is_default,
                       persona_prompt, greeting, llm_provider, llm_model, status
                  FROM rag_bots
                 ORDER BY is_default DESC, display_name
                """, (rs, i) -> {
            long id = rs.getLong("id");
            return new BotDef(
                    id, rs.getString("slug"), rs.getString("display_name"),
                    rs.getString("description"), rs.getString("teams_app_id"),
                    rs.getBoolean("is_default"), rs.getString("persona_prompt"),
                    rs.getString("greeting"), rs.getString("llm_provider"),
                    rs.getString("llm_model"), rs.getString("status"),
                    collections.getOrDefault(id, Set.of()),
                    audienceGroups.getOrDefault(id, Set.of()),
                    audienceUsers.getOrDefault(id, Set.of()));
        });
    }

    private Map<Long, Set<String>> botCollections() {
        Map<Long, Set<String>> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT bc.bot_id, c.slug
                  FROM rag_bot_collections bc
                  JOIN rag_collections c ON c.id = bc.collection_id
                """, rs -> {
            out.computeIfAbsent(rs.getLong("bot_id"), k -> new LinkedHashSet<>())
                    .add(rs.getString("slug"));
        });
        return out;
    }

    private Map<Long, Set<String>> audience(String principalType) {
        Map<Long, Set<String>> out = new LinkedHashMap<>();
        jdbc.query("SELECT bot_id, principal_id FROM rag_bot_audience WHERE principal_type = ?",
                ps -> ps.setString(1, principalType),
                rs -> {
                    out.computeIfAbsent(rs.getLong("bot_id"), k -> new LinkedHashSet<>())
                            .add(rs.getString("principal_id").toLowerCase());
                });
        return out;
    }

    public long createBot(String slug, String displayName, String description, String createdBy) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO rag_bots (slug, display_name, description, created_by)
                    VALUES (?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, slug);
            ps.setString(2, displayName);
            ps.setString(3, description);
            ps.setString(4, createdBy);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public int updateBot(long id, String displayName, String description, String teamsAppId,
                         String personaPrompt, String greeting, String llmProvider,
                         String llmModel, String status) {
        return jdbc.update("""
                UPDATE rag_bots
                   SET display_name = ?, description = ?, teams_app_id = ?, persona_prompt = ?,
                       greeting = ?, llm_provider = ?, llm_model = ?, status = ?, updated_at = now()
                 WHERE id = ?
                """, displayName, description, blankToNull(teamsAppId), personaPrompt, greeting,
                blankToNull(llmProvider), blankToNull(llmModel), status, id);
    }

    /**
     * Dat bot mac dinh. Bo co cu TRUOC trong cung mot transaction cua caller, neu khong
     * chi muc {@code idx_rag_bots_one_default} se chan.
     */
    public void setDefaultBot(long id) {
        jdbc.update("UPDATE rag_bots SET is_default = false WHERE is_default");
        jdbc.update("UPDATE rag_bots SET is_default = true WHERE id = ?", id);
    }

    public int deleteBot(long id) {
        return jdbc.update("DELETE FROM rag_bots WHERE id = ? AND NOT is_default", id);
    }

    public void setBotCollections(long botId, List<String> slugs) {
        jdbc.update("DELETE FROM rag_bot_collections WHERE bot_id = ?", botId);
        for (String slug : slugs) {
            if (slug == null || slug.isBlank()) continue;
            jdbc.update("""
                    INSERT INTO rag_bot_collections (bot_id, collection_id)
                    SELECT ?, id FROM rag_collections WHERE slug = ?
                    ON CONFLICT DO NOTHING
                    """, botId, slug.strip());
        }
    }

    public void setBotAudience(long botId, List<String> groupIds, List<String> userIds,
                               Map<String, String> names) {
        jdbc.update("DELETE FROM rag_bot_audience WHERE bot_id = ?", botId);
        insertAudience(botId, "GROUP", groupIds, names);
        insertAudience(botId, "USER", userIds, names);
    }

    private void insertAudience(long botId, String type, List<String> ids,
                                Map<String, String> names) {
        if (ids == null) return;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            jdbc.update("""
                    INSERT INTO rag_bot_audience (bot_id, principal_type, principal_id, display_name)
                    VALUES (?, ?, ?::uuid, ?)
                    ON CONFLICT (bot_id, principal_type, principal_id)
                    DO UPDATE SET display_name = EXCLUDED.display_name
                    """, botId, type, id.strip(),
                    names == null ? null : names.get(id.strip()));
        }
    }

    // ============================================================ Rang buoc kenh

    public List<ChannelBinding> channelBindings() {
        return jdbc.query("""
                SELECT bc.id, bc.bot_id, b.slug, bc.team_aad_group_id, bc.channel_id
                  FROM rag_bot_channels bc
                  JOIN rag_bots b ON b.id = bc.bot_id
                """, (rs, i) -> new ChannelBinding(
                rs.getLong("id"), rs.getLong("bot_id"), rs.getString("slug"),
                rs.getString("team_aad_group_id").toLowerCase(), rs.getString("channel_id")));
    }

    public void bindChannel(long botId, String teamAadGroupId, String channelId, String createdBy) {
        jdbc.update("""
                INSERT INTO rag_bot_channels (bot_id, team_aad_group_id, channel_id, created_by)
                VALUES (?, ?::uuid, ?, ?)
                ON CONFLICT (team_aad_group_id, COALESCE(channel_id, ''))
                DO UPDATE SET bot_id = EXCLUDED.bot_id
                """, botId, teamAadGroupId.strip(), blankToNull(channelId), createdBy);
    }

    public int unbindChannel(long id) {
        return jdbc.update("DELETE FROM rag_bot_channels WHERE id = ?", id);
    }

    // ============================================================ Grant

    public List<Grant> grants() {
        return jdbc.query("""
                SELECT id, principal_type, principal_id, scope_type, scope_id, role, display_name
                  FROM rag_grants ORDER BY scope_type, scope_id
                """, (rs, i) -> new Grant(
                rs.getLong("id"), rs.getString("principal_type"),
                rs.getString("principal_id").toLowerCase(), rs.getString("scope_type"),
                rs.getLong("scope_id"), rs.getString("role"), rs.getString("display_name")));
    }

    public void grant(String principalType, String principalId, String scopeType, long scopeId,
                      String role, String displayName, String grantedBy) {
        jdbc.update("""
                INSERT INTO rag_grants (principal_type, principal_id, scope_type, scope_id,
                                        role, display_name, granted_by)
                VALUES (?, ?::uuid, ?, ?, ?, ?, ?)
                ON CONFLICT (principal_type, principal_id, scope_type, scope_id, role)
                DO UPDATE SET display_name = EXCLUDED.display_name
                """, principalType, principalId.strip(), scopeType, scopeId, role,
                displayName, grantedBy);
    }

    public int revoke(long id) {
        return jdbc.update("DELETE FROM rag_grants WHERE id = ?", id);
    }

    /** Danh sach category dang thuc su co trong kho tai lieu, ke ca chua khai collection. */
    public List<String> orphanCategories() {
        return jdbc.queryForList("""
                SELECT DISTINCT d.category FROM rag_documents d
                 WHERE d.category IS NOT NULL AND d.category <> ''
                   AND NOT EXISTS (SELECT 1 FROM rag_collections c WHERE c.slug = d.category)
                 ORDER BY 1
                """, String.class);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    static List<String> safeList(List<String> v) {
        return v == null ? new ArrayList<>() : v;
    }
}
