package com.ai.aiagent.store;

import com.ai.aiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Kiem tra so chieu embedding trong DB co khop cau hinh khong, ngay luc khoi dong.
 *
 * Truoc day khong co buoc nay: doi model embedding sang so chieu khac chi bao loi
 * LUC INSERT (giua job nap lieu), rat kho lan ra nguyen nhan. Gio sai la biet ngay.
 */
@Component
@Slf4j
public class SchemaValidator {

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final JobRepository jobRepository;
    private final RagProperties props;

    public SchemaValidator(ChunkRepository chunkRepository,
                           DocumentRepository documentRepository,
                           JobRepository jobRepository,
                           RagProperties props) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.jobRepository = jobRepository;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        Integer actual = chunkRepository.actualEmbeddingDimensions();
        int configured = props.getEmbedding().getDimensions();

        if (actual == null) {
            log.warn("Khong doc duoc so chieu cot embedding tu DB - bo qua kiem tra.");
        } else if (actual != configured) {
            log.error("""
                    ============================================================
                    SO CHIEU EMBEDDING KHONG KHOP
                      DB dang la : vector({})
                      Cau hinh   : {} ({} / {})
                    Vector cua CAU HOI va cua TAI LIEU buoc phai cung mot model.
                    Hoac tra rag.embedding.dimensions ve {}, hoac tao lai schema voi
                    so chieu moi roi NAP LAI (re-ingest) toan bo tai lieu.
                    ============================================================""",
                    actual, configured, props.getEmbedding().getProvider(),
                    props.getEmbedding().modelName(), actual);
        } else {
            log.info("Schema OK: vector({}) khop cau hinh, {} tai lieu / {} chunk.",
                    actual, documentRepository.countAll(), chunkRepository.count());
        }

        int orphaned = jobRepository.markOrphanedAsInterrupted();
        if (orphaned > 0) {
            log.info("Danh dau {} job nap lieu bi ngat o lan chay truoc.", orphaned);
        }
    }
}
