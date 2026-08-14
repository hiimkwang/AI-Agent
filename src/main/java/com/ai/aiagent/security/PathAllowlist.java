package com.ai.aiagent.security;

import com.ai.aiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Chan doc file tuy y tren may chu.
 *
 * Endpoint {@code /admin/ingest-folder} truoc day nhan DUONG DAN BAT KY, nghia la
 * ai goi duoc API cung bat server nhung file Office o cho nao cung duoc vao vector
 * store roi doc lai qua {@code /chat}. Gio moi duong dan phai:
 *   1) nam trong mot trong cac thu muc goc da khai bao ({@code rag.ingestion.allowed-roots}),
 *   2) sau khi chuan hoa (resolve symlink, bo {@code ..}) VAN nam trong thu muc do.
 *
 * Khong khai bao thu muc goc nao => chan hoan toan.
 */
@Component
@Slf4j
public class PathAllowlist {

    private final List<Path> roots = new ArrayList<>();

    public PathAllowlist(RagProperties properties) {
        String configured = properties.getIngestion().getAllowedRoots();
        if (configured != null && !configured.isBlank()) {
            for (String raw : configured.split(",")) {
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    roots.add(Paths.get(trimmed).toAbsolutePath().normalize().toRealPath());
                } catch (IOException e) {
                    log.warn("Thu muc goc '{}' khong ton tai -> bo qua.", trimmed);
                }
            }
        }
        if (roots.isEmpty()) {
            log.warn("rag.ingestion.allowed-roots dang rong -> endpoint /admin/ingest-folder "
                    + "se tu choi moi duong dan. Dat RAG_ALLOWED_ROOTS de bat.");
        } else {
            log.info("Thu muc duoc phep nap tu may chu: {}", roots);
        }
    }

    public boolean hasRoots() {
        return !roots.isEmpty();
    }

    public List<String> configuredRoots() {
        return roots.stream().map(Path::toString).toList();
    }

    /**
     * @return duong dan da chuan hoa neu hop le
     * @throws SecurityException neu duong dan nam ngoai cac thu muc duoc phep
     */
    public Path requireAllowedDirectory(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("Duong dan trong.");
        }
        if (roots.isEmpty()) {
            throw new SecurityException("Chua cau hinh rag.ingestion.allowed-roots "
                    + "nen khong duoc phep nap tu thu muc tren may chu.");
        }
        Path resolved;
        try {
            resolved = Paths.get(candidate).toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Duong dan khong ton tai: " + candidate);
        }
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Khong phai thu muc: " + candidate);
        }
        for (Path root : roots) {
            if (resolved.startsWith(root)) {
                return resolved;
            }
        }
        throw new SecurityException("Duong dan nam ngoai cac thu muc duoc phep: " + candidate);
    }

    /** Chan ky tu duong dan trong ten file upload (chong ghi ra ngoai thu muc tam). */
    public static String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) return "upload.bin";
        String name = Paths.get(original).getFileName().toString();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return "upload.bin";
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
