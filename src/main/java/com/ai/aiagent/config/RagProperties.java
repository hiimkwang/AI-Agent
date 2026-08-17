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

    /**
     * Model embedding UNG VIEN, chay song song voi model dang dung de so sanh.
     *
     * Ly do ton tai: doi model embedding la thay doi dat gia nhat trong he thong - phai
     * tao lai schema va nap lai TOAN BO tai lieu, va sai thi phai lam lai lan nua. Nhung
     * neu khong thu duoc truoc thi quyet dinh chi dua tren cam giac.
     *
     * Co che nay nhung lai CHINH cac chunk dang co bang model ung vien vao mot bang phu
     * (chi {@code chunk_id + embedding}), roi do recall@k/MRR cua hai ben tren cung mot
     * bo cau hoi. Index dang chay khong bi dong den, nen thu nghiem an toan tuyet doi.
     */
    @Getter @Setter
    public static class Trial {
        private boolean enabled = false;
        /** OPENAI | OLLAMA | LOCAL */
        private String provider = "OPENAI";
        private String openaiModel = "text-embedding-3-large";
        private String ollamaModel = "bge-m3";
        private int dimensions = 3072;
        /** Bang phu chi chua {@code chunk_id + embedding}. */
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
        /**
         * Mo rong viet tat/thuat ngu bang bang {@code rag_synonyms} truoc khi truy xuat.
         * Them MOT bien the truy van, khong thay the cau goc - nen khong lam mat tu khoa
         * nguoi dung da go.
         */
        private boolean glossaryEnabled = true;
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
        /**
         * Nhan dien {@code Phan/Chuong/Muc/Dieu/Phu luc} lam moc cau truc, ke ca khi tai
         * lieu khong co heading Markdown nao (thuong gap voi file chuyen tu PDF/Word).
         * Nho vay chunk khong bao gio bi cat ngang giua mot Dieu.
         */
        private boolean legalStructureEnabled = true;
        /**
         * Gan ten tai lieu + so hieu + ngay hieu luc vao van ban DEM DI NHUNG.
         * Khong co buoc nay, thong tin do khong nam trong vector nen cau hoi dang
         * "quy dinh so bao nhieu", "van ban nao quy dinh" gan nhu chac chan truot.
         */
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

    /** Nhat ky thao tac quan tri - xem {@code com.ai.aiagent.audit.AuditFilter}. */
    @Getter @Setter
    public static class Audit {
        private boolean enabled = true;
        /**
         * Ghi ca cac luot DOC (GET). Mac dinh tat vi luot doc nhieu gap hang chuc lan
         * va lam loang nhung dong thuc su quan trong. Bat khi quy dinh noi bo yeu cau
         * chung minh "ai da xem gi".
         */
        private boolean includeRead = false;
        /** Do dai toi da cua than request duoc luu lai. */
        private int maxPayloadChars = 2000;
        /**
         * Chi bat khi ung dung THUC SU dung sau reverse proxy. Neu khong, bat ky ai
         * cung tu dat duoc {@code X-Forwarded-For} va lam sai lech nhat ky.
         */
        private boolean trustForwardedFor = false;
    }

    /**
     * Vong doi du lieu. Truoc day chi co endpoint xoa THU CONG, nghia la tren thuc te
     * cau hoi cua nguoi dung duoc luu vo thoi han.
     */
    @Getter @Setter
    public static class Retention {
        private boolean enabled = true;
        /** Gio chay hang ngay (0-23). Dat ngoai gio lam viec. */
        private int runAtHour = 2;
        /** Hoi thoai khong hoat dong qua so ngay nay se bi xoa. {@code <= 0} = giu mai. */
        private int conversationDays = 180;
        /**
         * Nhat ky kiem toan giu lau hon HAN nhieu so voi hoi thoai: day la thu de giai
         * trinh voi kiem toan, khong phai du lieu van hanh. Mac dinh 2 nam.
         */
        private int auditDays = 730;
        /** Ban ghi job nap lieu da ket thuc. */
        private int jobDays = 90;
    }

    /**
     * OCR cho PDF ban scan.
     *
     * MAC DINH TAT: moi trang la mot loi goi model thi giac, nen bat len ma nap ca kho
     * tai lieu la mot khoan chi phi that su - phai la quyet dinh co y thuc.
     */
    @Getter @Setter
    public static class Ocr {
        private boolean enabled = false;
        /** OPENAI | ANTHROPIC. Ca hai deu nhan anh base64. */
        private String provider = "OPENAI";
        private String model = "gpt-4o-mini";
        /** DPI khi ket xuat trang PDF thanh anh. 150 la diem can bang net/dung luong. */
        private int dpi = 150;
        /**
         * Tran so trang moi tai lieu. Mot cong van 5 trang thi khong sao, nhung mot ban
         * scan 800 trang lot vao se lang le tieu het han muc API.
         */
        private int maxPages = 60;
        private int concurrency = 4;
        private int timeoutSeconds = 120;
        /**
         * Chay OCR ca khi PDF CO text nhung qua it (chu yeu la trang bia scan kem vai
         * dong metadata). Nguong tinh bang ky tu tren mot trang; 0 = chi OCR khi khong
         * boc duoc chu nao.
         */
        private int minCharsPerPage = 80;
    }

    /**
     * Quet virus file nap vao (giao thuc clamd INSTREAM).
     *
     * Mac dinh TAT de moi truong dev khong phai dung ClamAV. Bat o moi truong that.
     */
    @Getter @Setter
    public static class Antivirus {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 3310;
        private int timeoutSeconds = 30;
        /**
         * Khi khong ket noi duoc toi clamd: {@code true} = TU CHOI nap file.
         *
         * Mac dinh dong, cung nguyen tac voi {@code EntraScopeService} khi Graph loi -
         * "khong kiem tra duoc" phai co nghia la "khong cho qua", khong phai "cho qua".
         */
        private boolean failClosed = true;
    }
}
