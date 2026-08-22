package com.ai.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "rag")
@Getter
@Setter
public class RagProperties {

    private final Llm llm = new Llm();
    private final Embedding embedding = new Embedding();
    private final OpenAi openai = new OpenAi();
    private final Anthropic anthropic = new Anthropic();
    private final Gemini gemini = new Gemini();
    private final Ollama ollama = new Ollama();
    private final Internal internal = new Internal();
    private final Store store = new Store();
    private final Retrieval retrieval = new Retrieval();
    private final Rerank rerank = new Rerank();
    private final Cohere cohere = new Cohere();
    private final QueryRewrite queryRewrite = new QueryRewrite();
    private final Convert convert = new Convert();
    private final Chunking chunking = new Chunking();
    private final Ingestion ingestion = new Ingestion();
    private final Cache cache = new Cache();
    private final Observability observability = new Observability();
    private final Chat chat = new Chat();
    private final Grants grants = new Grants();
    private final Audit audit = new Audit();
    private final Retention retention = new Retention();
    private final Ocr ocr = new Ocr();
    private final Antivirus antivirus = new Antivirus();

    @Getter @Setter
    public static class Llm {
        private String defaultProvider = "OPENAI";
        private String defaultModel = "gpt-4o-mini";
        private double temperature = 0.0;
        private int maxOutputTokens = 2048;
        private int timeoutSeconds = 120;
    }

    @Getter @Setter
    public static class Embedding {
        private String provider = "OPENAI";
        private int dimensions = 1536;
        private String openaiModel = "text-embedding-3-small";
        private String ollamaModel = "bge-m3";

        private final Trial trial = new Trial();

        public String modelName() {
            return modelNameOf(provider, openaiModel, ollamaModel);
        }

        static String modelNameOf(String provider, String openaiModel, String ollamaModel) {
            return switch (provider == null ? "" : provider.toUpperCase()) {
                case "OLLAMA" -> ollamaModel;
                case "LOCAL" -> "all-minilm-l6-v2";
                default -> openaiModel;
            };
        }
    }

    @Getter @Setter
    public static class Trial {
        private boolean enabled = false;
        private String provider = "OPENAI";
        private String openaiModel = "text-embedding-3-large";
        private String ollamaModel = "bge-m3";
        private int dimensions = 3072;
        private String table = "rag_chunks_trial";
        private int batchSize = 64;

        public String modelName() {
            return Embedding.modelNameOf(provider, openaiModel, ollamaModel);
        }
    }

    @Getter @Setter
    public static class OpenAi {
        private String apiKey = "";
        private String baseUrl = "";
        private String chatModel = "gpt-4o-mini";
    }

    @Getter @Setter
    public static class Anthropic {
        private String apiKey = "";
        private String chatModel = "claude-opus-5";
        private boolean thinkingEnabled = false;
        private String effort = "low";
    }

    @Getter @Setter
    public static class Gemini {
        private String apiKey = "";
        private String chatModel = "gemini-2.0-flash";
    }

    @Getter @Setter
    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private String chatModel = "qwen2.5:7b";
    }

    @Getter @Setter
    public static class Internal {
        private String provider = "OPENAI";
        private String model = "gpt-4o-mini";
    }

    @Getter @Setter
    public static class Store {
        private String chunkTable = "rag_chunks";
        private int insertBatchSize = 500;
    }

    @Getter @Setter
    public static class Retrieval {
        private boolean hybridEnabled = true;
        private int vectorTopK = 24;
        private int fulltextTopK = 24;
        private int rrfK = 60;
        private int candidates = 36;
        private int topK = 6;
        private int filterOverfetchMultiplier = 4;
        private int maxOverfetch = 400;
        private boolean recencyBoostEnabled = true;
        private double recencyBoostWeight = 0.15;
        private boolean excludeExpired = true;
        private boolean multiQueryEnabled = true;
        private boolean hydeEnabled = false;
        private double minRerankScore = 0.30;
        private double minVectorScore = 0.20;
        private boolean abstainWhenBelowThreshold = true;
        private boolean clarifyAmbiguousEnabled = true;
        private boolean glossaryEnabled = true;
    }

    @Getter @Setter
    public static class Rerank {
        private String provider = "LLM";
        /**
         * Candidates per LLM rerank call. All 36 in one prompt is ~32 KB of Vietnamese and a
         * small model answers it by skimming: measured on UAT it scored 2 of 36 and skipped the
         * document the question was actually about.
         */
        private int batchSize = 12;
    }

    @Getter @Setter
    public static class Cohere {
        private String apiKey = "";
        private String rerankModel = "rerank-multilingual-v3.0";
    }

    @Getter @Setter
    public static class QueryRewrite {
        private boolean enabled = true;
        private int maxHistoryTurns = 6;
        private int maxHistoryChars = 3000;
    }

    @Getter @Setter
    public static class Convert {
        private boolean pdfEnabled = true;
        private boolean htmlEnabled = true;
        private boolean officeEnabled = true;
        private boolean storeMarkdown = true;
        private boolean pdfDropRepeatedLines = true;
        private int maxMarkdownChars = 4_000_000;
    }

    @Getter @Setter
    public static class Chunking {
        private String strategy = "MARKDOWN_HEADING";
        private int parentMaxChars = 2400;
        private int childMaxChars = 600;
        private int childOverlapChars = 120;
        private int minSectionChars = 200;
        private boolean prefixHeadingPath = true;
        private boolean dedupeWithinDocument = true;
        private boolean legalStructureEnabled = true;
        private boolean prefixDocumentIdentity = true;
    }

    @Getter @Setter
    public static class Ingestion {
        private boolean contextualEnabled = false;
        private int contextualConcurrency = 8;
        private int embedBatchSize = 96;
        private int embedMaxRetries = 4;
        private long embedRetryBaseDelayMs = 800;
        private int jobConcurrency = 2;
        private boolean skipUnchanged = true;
        private String allowedRoots = "";
    }

    @Getter @Setter
    public static class Cache {
        private boolean enabled = true;
        private int ttlMinutes = 180;
        private int maxEntries = 5000;
        private boolean semanticEnabled = true;
        private double semanticThreshold = 0.97;

        /**
         * Answers matching this are never stored. Matched against the answer with diacritics
         * stripped and lower-cased, so the pattern itself is written unaccented.
         *
         * <p>The system prompt tells the model to say it found nothing when the passages fall
         * short, and that is precisely the answer that must not be frozen: documents keep
         * arriving and retrieval keeps being tuned, so today's "not found" is tomorrow's wrong
         * answer, served in 200 ms with no way for the user to tell it is stale. It is also the
         * cheapest answer to recompute. Over-matching only costs a cache miss, under-matching
         * costs a wrong answer for the whole TTL, so this pattern is deliberately generous.
         * Blank disables the check.
         */
        private String refusalPattern = DEFAULT_REFUSAL_PATTERN;
    }

    public static final String DEFAULT_REFUSAL_PATTERN =
            "khong tim thay (thong tin|noi dung|tai lieu|du lieu|quy dinh)"
                    + "|khong (co|du) thong tin"
                    + "|tai lieu( noi bo)? (khong|chua) (de cap|neu|noi|cung cap)"
                    + "|khong de cap den"
                    + "|khong the tra loi";

    @Getter @Setter
    public static class Observability {
        private boolean logQuestions = false;
        private boolean logAnswers = false;
        private boolean logCandidates = false;
    }

    @Getter @Setter
    public static class Chat {
        private boolean citationsEnabled = true;

        /**
         * How many previous turns of the conversation the ANSWERING model sees. 0 disables it.
         *
         * <p>Separate from {@code rag.query-rewrite.max-history-turns}, which feeds the little
         * rewrite model only. Those are different jobs: the rewriter needs enough context to
         * resolve a pronoun, the answerer needs to know what it already said. Keeping the answer
         * window short is deliberate - every turn is charged on every question.
         */
        private int historyTurns = 3;

        /** Cap on one history turn. Assistant answers run to 1500+ chars and would dwarf the
         *  retrieved passages. */
        private int historyCharsPerTurn = 500;

        // Blank means "use the built-in default". Keeping the defaults in code and
        // treating config as an override means an empty value resets instead of
        // wiping the prompt - a wiped system prompt would silently drop the
        // document-only and prompt-injection rules.
        private String systemPrompt = "";
        private String notFoundMessage = "";
        private String notRelevantMessage = "";

        /**
         * Appended when the model itself says it found nothing AND there is no concrete topic to
         * suggest. Blank uses the built-in default. When suggestions exist the UI shows them as
         * clickable chips instead, which is more useful than a sentence.
         */
        private String noAnswerFollowUp = "";

        // Greetings retrieve nothing, so without this they hit the abstain path and
        // get answered with "khong tim thay tai lieu".
        private boolean smallTalkEnabled = true;
        private String smallTalkPattern = "";
        private String smallTalkReply = "";
    }

    @Getter @Setter
    public static class Grants {
        private boolean enabled = true;

        // A delegated owner may only share a collection with groups they belong to.
        // That still lets them pick a company-wide group, which would publish the
        // whole collection in one click - list those object ids here to forbid them.
        private List<String> aclDeniedGroups = new ArrayList<>();

        // Whether an owner may edit persona/greeting of the bots granted to them.
        private boolean ownersMayEditPersona = true;
    }

    @Getter @Setter
    public static class Audit {
        private boolean enabled = true;
        private boolean includeRead = false;
        private int maxPayloadChars = 2000;
        private boolean trustForwardedFor = false;
    }

    @Getter @Setter
    public static class Retention {
        private boolean enabled = true;
        private int runAtHour = 2;
        private int conversationDays = 180;
        private int auditDays = 730;
        private int jobDays = 90;
    }

    @Getter @Setter
    public static class Ocr {
        private boolean enabled = false;
        private String provider = "OPENAI";
        private String model = "gpt-4o-mini";
        private int dpi = 150;
        private int maxPages = 60;
        private int concurrency = 4;
        private int timeoutSeconds = 120;
        private int minCharsPerPage = 80;
    }

    @Getter @Setter
    public static class Antivirus {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 3310;
        private int timeoutSeconds = 30;
        private boolean failClosed = true;
    }
}
