package com.ai.aiagent.settings;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.llm.LlmClientFactory;
import com.ai.aiagent.store.SettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cau hinh API key / model cua tung provider LLM, sua duoc luc runtime qua trang admin.
 *
 * TACH RIENG khoi {@link RagSettingsService} vi ly do bao mat: {@code GET /api/v1/rag/settings}
 * (do RagSettingsService phuc vu) duoc phep goi boi MOI nguoi dung da xac thuc, khong chi
 * ADMIN (trang chat can biet model dang dung) - dua API key vao do se lo key cho nguoi
 * dung thuong. Service nay chi duoc expose qua {@code /api/v1/rag/admin/providers}, da
 * bi khoa {@code hasRole("ADMIN")} boi {@code SecurityConfig}.
 *
 * Dung chung bang {@code rag_settings} voi RagSettingsService nhung tach namespace bang
 * tien to {@link #KEY_PREFIX} de hai ben khong dam vao khoa cua nhau.
 *
 * KHONG bao gio tra full API key ra ngoai - {@link #snapshot()} chi tra "co cau hinh hay
 * khong" kem vai ky tu cuoi. Khong dua embedding provider/dimensions vao day: doi luc
 * runtime ma khong tao lai schema + nap lai toan bo se lam vector cau hoi va vector tai
 * lieu lech model (xem bat bien trong CLAUDE.md).
 *
 * {@link #connect} goi thang API cua tung nha cung cap de VUA xac thuc key VUA lay danh
 * sach model that (thay vi bat nguoi dung go tay ten model) - dung REST thu cong bang
 * {@code java.net.http.HttpClient}, theo dung pattern da co o
 * {@code com.ai.aiagent.llm.GeminiLlmClient}.
 */
@Service
@Slf4j
public class ProviderSettingsService {

    public static final String KEY_PREFIX = "provider.";
    private static final String MODELS_SUFFIX = ".models";

    private static final String K_OPENAI_KEY = "openai.apiKey";
    private static final String K_OPENAI_BASE_URL = "openai.baseUrl";
    private static final String K_OPENAI_MODEL = "openai.chatModel";
    private static final String K_ANTHROPIC_KEY = "anthropic.apiKey";
    private static final String K_ANTHROPIC_MODEL = "anthropic.chatModel";
    private static final String K_ANTHROPIC_THINKING = "anthropic.thinkingEnabled";
    private static final String K_ANTHROPIC_EFFORT = "anthropic.effort";
    private static final String K_GEMINI_KEY = "gemini.apiKey";
    private static final String K_GEMINI_MODEL = "gemini.chatModel";
    private static final String K_OLLAMA_BASE_URL = "ollama.baseUrl";
    private static final String K_OLLAMA_MODEL = "ollama.chatModel";
    private static final String K_COHERE_KEY = "cohere.apiKey";
    private static final String K_COHERE_MODEL = "cohere.rerankModel";

    private final RagProperties props;
    private final SettingsRepository repository;
    private final LlmClientFactory clients;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** provider (thuong, vd "openai") -> danh sach model that lay duoc lan Ket noi gan nhat. */
    private final Map<String, List<String>> modelsCache = new ConcurrentHashMap<>();

    public ProviderSettingsService(RagProperties props, SettingsRepository repository,
                                   LlmClientFactory clients) {
        this.props = props;
        this.repository = repository;
        this.clients = clients;
    }

    @PostConstruct
    void init() {
        Map<String, String> stored = repository.loadAll();
        int applied = 0;
        for (Map.Entry<String, String> e : stored.entrySet()) {
            if (!e.getKey().startsWith(KEY_PREFIX)) continue;
            String key = e.getKey().substring(KEY_PREFIX.length());
            try {
                if (key.endsWith(MODELS_SUFFIX)) {
                    String provider = key.substring(0, key.length() - MODELS_SUFFIX.length());
                    modelsCache.put(provider, readModelsJson(e.getValue()));
                    applied++;
                } else if (applyOne(key, e.getValue())) {
                    applied++;
                }
            } catch (Exception ex) {
                log.warn("Bo qua cau hinh provider luu san '{}': {}", e.getKey(), ex.getMessage());
            }
        }
        log.info("Da ap {} cau hinh provider luu san trong DB.", applied);
    }

    /** Trang thai hien tai cua tung provider, API key luon duoc CHE - khong tra full key. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> openai = new LinkedHashMap<>();
        mask(openai, props.getOpenai().getApiKey());
        openai.put("baseUrl", props.getOpenai().getBaseUrl());
        openai.put("chatModel", props.getOpenai().getChatModel());
        openai.put("models", modelsOf("openai", props.getOpenai().getChatModel()));
        out.put("openai", openai);

        Map<String, Object> anthropic = new LinkedHashMap<>();
        mask(anthropic, props.getAnthropic().getApiKey());
        anthropic.put("chatModel", props.getAnthropic().getChatModel());
        anthropic.put("thinkingEnabled", props.getAnthropic().isThinkingEnabled());
        anthropic.put("effort", props.getAnthropic().getEffort());
        anthropic.put("models", modelsOf("anthropic", props.getAnthropic().getChatModel()));
        out.put("anthropic", anthropic);

        Map<String, Object> gemini = new LinkedHashMap<>();
        mask(gemini, props.getGemini().getApiKey());
        gemini.put("chatModel", props.getGemini().getChatModel());
        gemini.put("models", modelsOf("gemini", props.getGemini().getChatModel()));
        out.put("gemini", gemini);

        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("baseUrl", props.getOllama().getBaseUrl());
        ollama.put("chatModel", props.getOllama().getChatModel());
        ollama.put("models", modelsOf("ollama", props.getOllama().getChatModel()));
        out.put("ollama", ollama);

        Map<String, Object> cohere = new LinkedHashMap<>();
        mask(cohere, props.getCohere().getApiKey());
        cohere.put("rerankModel", props.getCohere().getRerankModel());
        cohere.put("models", modelsOf("cohere", props.getCohere().getRerankModel()));
        out.put("cohere", cohere);

        return out;
    }

    /**
     * Ap va luu nhieu cau hinh mot luc. Khoa nao KHONG co mat trong {@code changes} thi
     * giu nguyen - de xoa han mot API key, dung {@link #clearKey(String)} thay vi gui
     * chuoi rong (tranh nham lan "khong doi" voi "xoa").
     */
    public List<String> update(Map<String, Object> changes, String updatedBy) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? null : String.valueOf(e.getValue()).strip();
            if (value == null || value.isBlank()) continue; // rong = bo qua, khong ghi de
            if (!applyOne(key, value)) {
                throw new IllegalArgumentException("Khoa cau hinh provider khong ho tro: '" + key + "'.");
            }
            repository.put(KEY_PREFIX + key, value, updatedBy);
            changed.add(key);
        }
        if (!changed.isEmpty()) {
            clients.clearCache();
            log.info("Da cap nhat {} cau hinh provider boi {}: {}", changed.size(), updatedBy, changed);
        }
        return changed;
    }

    /** Xoa han mot API key (khac voi de trong o {@link #update} - la "khong doi"). */
    public void clearKey(String providerName) {
        String key;
        switch (providerName == null ? "" : providerName.toLowerCase()) {
            case "openai" -> { props.getOpenai().setApiKey(""); key = K_OPENAI_KEY; }
            case "anthropic" -> { props.getAnthropic().setApiKey(""); key = K_ANTHROPIC_KEY; }
            case "gemini" -> { props.getGemini().setApiKey(""); key = K_GEMINI_KEY; }
            case "cohere" -> { props.getCohere().setApiKey(""); key = K_COHERE_KEY; }
            default -> throw new IllegalArgumentException(
                    "Provider '" + providerName + "' khong co API key de xoa.");
        }
        repository.remove(KEY_PREFIX + key);
        clients.clearCache();
        log.info("Da xoa API key cua provider '{}'.", providerName);
    }

    /**
     * Xac thuc key/baseUrl bang cach GOI THAT API liet ke model cua provider, roi luu
     * lai neu thanh cong. That bai thi KHONG ghi gi ca - giu nguyen cau hinh dang chay.
     *
     * @param apiKey  key nguoi dung vua go trong form, rong = dung key da luu
     * @param baseUrl baseUrl nguoi dung vua go, rong = dung baseUrl da luu
     */
    public List<String> connect(String providerName, String apiKey, String baseUrl, String updatedBy) {
        String provider = providerName == null ? "" : providerName.toLowerCase();
        List<String> models;
        try {
            models = switch (provider) {
                case "openai" -> {
                    String key = effective(apiKey, props.getOpenai().getApiKey());
                    String url = effective(baseUrl, props.getOpenai().getBaseUrl());
                    requireKey(key, "OpenAI");
                    yield fetchOpenAiModels(key, url);
                }
                case "anthropic" -> {
                    String key = effective(apiKey, props.getAnthropic().getApiKey());
                    requireKey(key, "Anthropic");
                    yield fetchAnthropicModels(key);
                }
                case "gemini" -> {
                    String key = effective(apiKey, props.getGemini().getApiKey());
                    requireKey(key, "Gemini");
                    yield fetchGeminiModels(key);
                }
                case "ollama" -> {
                    String url = effective(baseUrl, props.getOllama().getBaseUrl());
                    yield fetchOllamaModels(url);
                }
                case "cohere" -> {
                    String key = effective(apiKey, props.getCohere().getApiKey());
                    requireKey(key, "Cohere");
                    yield fetchCohereModels(key);
                }
                default -> throw new IllegalArgumentException("Provider khong ho tro: '" + providerName + "'.");
            };
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalArgumentException(
                    "Khong ket noi duoc toi " + providerName + ": " + describe(e));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Loi khi ket noi toi " + providerName + ": " + describe(e));
        }

        // Chi ghi de key/baseUrl neu nguoi dung THUC SU go gia tri moi trong form
        Map<String, Object> toSave = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) toSave.put(provider + ".apiKey", apiKey.strip());
        if (baseUrl != null && !baseUrl.isBlank()) {
            toSave.put(provider + ".baseUrl", baseUrl.strip());
        }
        if (!toSave.isEmpty()) {
            update(toSave, updatedBy);
        }

        modelsCache.put(provider, models);
        try {
            repository.put(KEY_PREFIX + provider + MODELS_SUFFIX, mapper.writeValueAsString(models), updatedBy);
        } catch (Exception e) {
            log.warn("Khong luu duoc danh sach model cua '{}' (khong anh huong ket qua Ket noi): {}",
                    provider, e.getMessage());
        }
        clients.clearCache();
        log.info("Ket noi thanh cong toi '{}', lay duoc {} model.", provider, models.size());
        return models;
    }

    // ============================================================ Goi API tung provider

    private List<String> fetchOpenAiModels(String apiKey, String baseUrl) throws Exception {
        String base = (baseUrl == null || baseUrl.isBlank()) ? "https://api.openai.com" : trimSlash(baseUrl);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/models"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .GET().build();
        JsonNode root = send(req, "OpenAI");
        List<String> out = new ArrayList<>();
        for (JsonNode n : root.path("data")) {
            String id = n.path("id").asText(null);
            if (id == null) continue;
            String lower = id.toLowerCase();
            if (lower.contains("whisper") || lower.contains("tts") || lower.contains("dall-e")
                    || lower.contains("embedding") || lower.contains("moderation")) continue;
            out.add(id);
        }
        out.sort(String::compareTo);
        return out;
    }

    private List<String> fetchAnthropicModels(String apiKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/models"))
                .timeout(Duration.ofSeconds(15))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .GET().build();
        JsonNode root = send(req, "Anthropic");
        List<String> out = new ArrayList<>();
        for (JsonNode n : root.path("data")) {
            String id = n.path("id").asText(null);
            if (id != null) out.add(id);
        }
        return out;
    }

    private List<String> fetchGeminiModels(String apiKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey))
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        JsonNode root = send(req, "Gemini");
        List<String> out = new ArrayList<>();
        for (JsonNode n : root.path("models")) {
            boolean supportsChat = false;
            for (JsonNode m : n.path("supportedGenerationMethods")) {
                if ("generateContent".equals(m.asText())) { supportsChat = true; break; }
            }
            if (!supportsChat) continue;
            String name = n.path("name").asText(null);
            if (name == null) continue;
            out.add(name.startsWith("models/") ? name.substring("models/".length()) : name);
        }
        return out;
    }

    private List<String> fetchOllamaModels(String baseUrl) throws Exception {
        String base = (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:11434" : trimSlash(baseUrl);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        JsonNode root = send(req, "Ollama");
        List<String> out = new ArrayList<>();
        for (JsonNode n : root.path("models")) {
            String name = n.path("name").asText(null);
            if (name != null) out.add(name);
        }
        return out;
    }

    private List<String> fetchCohereModels(String apiKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.cohere.com/v1/models"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .GET().build();
        JsonNode root = send(req, "Cohere");
        List<String> out = new ArrayList<>();
        for (JsonNode n : root.path("models")) {
            boolean isRerank = false;
            for (JsonNode ep : n.path("endpoints")) {
                if ("rerank".equals(ep.asText())) { isRerank = true; break; }
            }
            if (!isRerank) continue;
            String name = n.path("name").asText(null);
            if (name != null) out.add(name);
        }
        return out;
    }

    private JsonNode send(HttpRequest req, String providerLabel) throws Exception {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new IllegalArgumentException(providerLabel + " tu choi API key (HTTP " + res.statusCode()
                    + ") - kiem tra lai key.");
        }
        if (res.statusCode() != 200) {
            throw new IllegalArgumentException(providerLabel + " tra ve loi HTTP " + res.statusCode()
                    + ": " + truncate(res.body()));
        }
        return mapper.readTree(res.body());
    }

    /**
     * Nhieu exception mang (vd {@code ConnectException} khi khong co gi lang nghe o
     * port do) tra ve {@code getMessage() == null}, khien thong bao loi hien "...: null"
     * rat kho hieu - fallback ve ten class exception trong truong hop do.
     */
    private static String describe(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String effective(String requested, String stored) {
        return (requested != null && !requested.isBlank()) ? requested.strip() : stored;
    }

    private static void requireKey(String key, String label) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Chua co API key cho " + label + " - nhap key truoc khi ket noi.");
        }
    }

    private List<String> modelsOf(String provider, String currentModel) {
        List<String> cached = modelsCache.get(provider);
        List<String> out = new ArrayList<>(cached == null ? List.of() : cached);
        if (currentModel != null && !currentModel.isBlank() && !out.contains(currentModel)) {
            out.add(0, currentModel);
        }
        return out;
    }

    private List<String> readModelsJson(String json) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode n : mapper.readTree(json)) {
            out.add(n.asText());
        }
        return out;
    }

    /** @return false neu khoa khong duoc ho tro. */
    private boolean applyOne(String key, String value) {
        switch (key) {
            case K_OPENAI_KEY -> props.getOpenai().setApiKey(value);
            case K_OPENAI_BASE_URL -> props.getOpenai().setBaseUrl(value);
            case K_OPENAI_MODEL -> props.getOpenai().setChatModel(value);
            case K_ANTHROPIC_KEY -> props.getAnthropic().setApiKey(value);
            case K_ANTHROPIC_MODEL -> props.getAnthropic().setChatModel(value);
            case K_ANTHROPIC_THINKING -> props.getAnthropic().setThinkingEnabled(Boolean.parseBoolean(value));
            case K_ANTHROPIC_EFFORT -> props.getAnthropic().setEffort(value);
            case K_GEMINI_KEY -> props.getGemini().setApiKey(value);
            case K_GEMINI_MODEL -> props.getGemini().setChatModel(value);
            case K_OLLAMA_BASE_URL -> props.getOllama().setBaseUrl(value);
            case K_OLLAMA_MODEL -> props.getOllama().setChatModel(value);
            case K_COHERE_KEY -> props.getCohere().setApiKey(value);
            case K_COHERE_MODEL -> props.getCohere().setRerankModel(value);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void mask(Map<String, Object> out, String apiKey) {
        boolean set = apiKey != null && !apiKey.isBlank();
        out.put("apiKeySet", set);
        out.put("apiKeyMasked", set
                ? "••••" + apiKey.substring(Math.max(0, apiKey.length() - 4))
                : null);
    }
}
