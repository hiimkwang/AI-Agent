package com.ai.aiagent.store;

import com.ai.aiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
            log.warn("Could not read the embedding column dimension from the database, "
                    + "skipping the check.");
        } else if (actual != configured) {
            log.error("""
                    ============================================================
                    EMBEDDING DIMENSION MISMATCH
                      Database : vector({})
                      Config   : {} ({} / {})
                    Question vectors and document vectors must come from the same
                    model. Either set rag.embedding.dimensions back to {}, or recreate
                    the schema at the new size and RE-INGEST every document.
                    ============================================================""",
                    actual, configured, props.getEmbedding().getProvider(),
                    props.getEmbedding().modelName(), actual);
        } else {
            log.info("Schema OK: vector({}) matches the configuration, {} document(s) / {} chunk(s).",
                    actual, documentRepository.countAll(), chunkRepository.count());
        }

        int orphaned = jobRepository.markOrphanedAsInterrupted();
        if (orphaned > 0) {
            log.info("Marked {} ingestion job(s) as interrupted by the previous shutdown.", orphaned);
        }
    }
}
