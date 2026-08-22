package com.ai.aiagent.bot;

import com.ai.aiagent.config.BotProperties;
import com.ai.aiagent.config.EntraProperties;
import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.platform.PlatformService;
import com.ai.aiagent.security.GraphDirectoryClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers "why is the Teams bot silent?" without an SSH session. Lives in the bot package
 * so it only exists when the bot does, and so it can read the connector's package-private
 * diagnostics. ADMIN only, via the {@code /api/v1/rag/admin/**} rule.
 */
@RestController
@RequestMapping("/api/v1/rag/admin")
@ConditionalOnProperty(prefix = "rag.bot", name = "enabled", havingValue = "true")
@Slf4j
public class BotDiagnosticsController {

    private final BotProperties props;
    private final EntraProperties entraProps;
    private final BotConnectorClient connector;
    private final PlatformService platform;
    private final ObjectProvider<GraphDirectoryClient> graph;

    public BotDiagnosticsController(BotProperties props, EntraProperties entraProps,
                                    BotConnectorClient connector, PlatformService platform,
                                    ObjectProvider<GraphDirectoryClient> graph) {
        this.props = props;
        this.entraProps = entraProps;
        this.connector = connector;
        this.platform = platform;
        this.graph = graph;
    }

    /**
     * @param probeToken asks Microsoft for an outbound token. Off by default so a
     *                   monitoring poll does not hammer the token endpoint.
     */
    @GetMapping("/bot-status")
    public Map<String, Object> status(
            @RequestParam(defaultValue = "false") boolean probeToken) {

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("azure", connector.describe());

        GraphDirectoryClient client = graph.getIfAvailable();
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("entraEnabled", entraProps.isEnabled());
        identity.put("graphReady", client != null && client.isReady());
        identity.put("unidentifiedDepartments", props.getUnidentifiedDepartments());
        out.put("identity", identity);

        PlatformService.Snapshot snapshot = platform.snapshot();
        List<BotDef> active = snapshot.bots().stream().filter(BotDef::isActive).toList();
        Map<String, Object> plat = new LinkedHashMap<>();
        plat.put("activeBots", active.stream().map(BotDef::slug).toList());
        plat.put("defaultBot", active.stream().filter(BotDef::isDefault)
                .map(BotDef::slug).findFirst().orElse(null));
        plat.put("botsWithoutCollections", active.stream()
                .filter(b -> b.collectionSlugs().isEmpty()).map(BotDef::slug).toList());
        plat.put("channelAllowedCollections", platform.channelAllowedSlugs());
        plat.put("channelBindings", snapshot.bindings().size());
        plat.put("aclConfigured", !platform.hasNoAcl());
        out.put("platform", plat);

        out.put("limits", Map.of(
                "workerThreads", props.getWorkerThreads(),
                "perUserPerMinute", props.getPerUserPerMinute(),
                "perBotPerMinute", props.getPerBotPerMinute(),
                "connectorTimeoutSeconds", props.getConnectorTimeoutSeconds(),
                "maxClockSkewSeconds", props.getMaxClockSkewSeconds(),
                "maxAnswerChars", props.getMaxAnswerChars(),
                "maxCitations", props.getMaxCitations()));

        out.put("readiness", readiness(active, client));

        if (probeToken) {
            out.put("outboundToken", connector.tokenProbe());
        }
        return out;
    }

    private static String firstFew(List<String> values) {
        int shown = Math.min(5, values.size());
        return String.join(", ", values.subList(0, shown))
                + (values.size() > shown ? ", … (" + values.size() + " tổng)" : "");
    }

    /** The ordered list of things that make the bot refuse or stay silent. */
    private List<String> readiness(List<BotDef> activeBots, GraphDirectoryClient client) {
        List<String> problems = new ArrayList<>();
        if (!props.isConfigured()) {
            problems.add("Thiếu rag.bot.app-id / app-password (hoặc tenant-id khi "
                    + "app-type=SINGLE_TENANT) ⇒ mọi request tới /api/messages bị 401.");
        }
        if (activeBots.isEmpty()) {
            problems.add("Chưa có bot nào đang hoạt động ⇒ bot từ chối mọi câu hỏi.");
        } else if (activeBots.stream().noneMatch(BotDef::isDefault)) {
            problems.add("Chưa đặt bot mặc định ⇒ chat riêng và các kênh chưa gán bot "
                    + "sẽ không có bot nào phục vụ.");
        }
        activeBots.stream().filter(b -> b.collectionSlugs().isEmpty()).findAny()
                .ifPresent(b -> problems.add("Bot '" + b.slug() + "' chưa được gán nhóm "
                        + "tài liệu nào ⇒ luôn từ chối trả lời."));
        boolean identifiable = entraProps.isEnabled()
                && client != null && client.isReady();
        if (!identifiable && props.getUnidentifiedDepartments().isBlank()) {
            problems.add("Không xác định được người hỏi (Entra tắt hoặc Graph chưa sẵn "
                    + "sàng) và rag.bot.unidentified-departments để rỗng ⇒ bot từ chối "
                    + "mọi người.");
        }
        if (platform.hasNoAcl()) {
            problems.add("Chưa nhóm tài liệu nào có ACL ⇒ quyền đọc đang lấy từ "
                    + "rag.entra.group-departments trong file cấu hình, không phải từ màn "
                    + "quản trị.");
        }
        // Both of these make the bot answer "khong tim thay" while an admin sees the document
        // fine on the web, so neither ever looks like a permission problem.
        List<String> uncategorized = platform.uncategorizedDocuments();
        if (!uncategorized.isEmpty()) {
            problems.add(uncategorized.size() + " tài liệu không có nhóm ⇒ chỉ quản trị viên "
                    + "đọc được, bot sẽ trả lời 'không tìm thấy' cho mọi người khác: "
                    + firstFew(uncategorized));
        }
        List<String> orphans = platform.orphanCategories();
        if (!orphans.isEmpty()) {
            problems.add("Có tài liệu thuộc nhóm chưa được khai ở màn quản trị ⇒ không ai ngoài "
                    + "quản trị viên đọc được. Hãy tạo nhóm tài liệu với đúng slug: "
                    + firstFew(orphans));
        }
        if (platform.channelAllowedSlugs().isEmpty()) {
            problems.add("Chưa nhóm tài liệu nào bật 'trả lời trong kênh' ⇒ bot chỉ trả "
                    + "lời trong chat riêng. Đây là mặc định an toàn, không phải lỗi.");
        }
        return problems;
    }
}
