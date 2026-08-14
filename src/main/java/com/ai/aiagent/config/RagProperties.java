package com.ai.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Toan bo tham so cua he thong RAG, bind tu tien to {@code rag.*}.
 *
 * CO Y DE MUTABLE (co setter): {@code RagSettingsService} sua truc tiep bean nay
 * luc runtime roi ghi xuong bang {@code rag_settings}, nho vay doi {@code top-k}
 * hay {@code min-rerank-score} khong con phai restart ung dung nua.
 *
 * Truoc day cac tham so nay bind bang {@code @Value} vao field cua tung service,
 * nen chi doc duoc 1 lan luc khoi tao bean.
 */
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

    @Getter @Setter
    public static class Llm {
        private String defaultProvider = "OPENAI";
        private String defaultModel = "gpt-4o-mini";
        private double temperature = 0.0;
        private int maxOutputTokens = 2048;
        private int timeoutSeconds = 120;
    }

    /**
     * Cau hinh embedding, tach RIENG khoi OpenAI.
     *
     * Truoc day embedding gan chat vao OpenAI: chon chat bang Ollama van phai co
     * OPENAI_API_KEY, nen OpenAI la SINGLE POINT OF FAILURE - OpenAI loi la ca he
     * thong chet, ke ca phan "local". Gio co the chay hoan toan offline.
     */
    @Getter @Setter
    public static class Embedding {
        /** OPENAI | OLLAMA | LOCAL */
        private String provider = "OPENAI";
        private int dimensions = 1536;
        private String openaiModel = "text-embedding-3-small";
        /** bge-m3 (1024 chieu) cho ket qua tot voi tieng Viet. */
        private String ollamaModel = "bge-m3";

        public String modelName() {
            return switch (provider == null ? "" : provider.toUpperCase()) {
                case "OLLAMA" -> ollamaModel;
                case "LOCAL" -> "all-minilm-l6-v2";
                default -> openaiModel;
            };
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
        /**
         * Claude Opus 5 bat thinking theo mac dinh. Voi hoi-dap co san ngu canh
         * thi tat di cho nhanh; khi tat, effort phai <= high (neu khong API tra 400).
         */
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
        /**
         * Nho model noi bo danh gia cau hoi co du ro rang de tim tai lieu khong, TRUOC
         * khi chay retrieval. Neu mo ho -> hoi lai nguoi dung ngay, khong tra loi dai.
         */
        private boolean clarifyAmbiguousEnabled = true;
    }

    @Getter @Setter
    public static class Rerank {
        private String provider = "LLM";
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
        /** Thu muc duy nhat duoc phep nap tu may chu; rong = chan hoan toan. */
        private String allowedRoots = "";
    }

    @Getter @Setter
    public static class Cache {
        private boolean enabled = true;
        private int ttlMinutes = 180;
        private int maxEntries = 5000;
        private boolean semanticEnabled = true;
        private double semanticThreshold = 0.97;
    }

    @Getter @Setter
    public static class Observability {
        private boolean logQuestions = false;
        private boolean logAnswers = false;
        private boolean logCandidates = false;
    }

    /** Cau hinh trai nghiem tra loi (khong lien quan retrieval/tuning). */
    @Getter @Setter
    public static class Chat {
        /** Hien danh sach trich dan nguon (giong NotebookLM) kem cau tra loi. */
        private boolean citationsEnabled = true;
    }
}
