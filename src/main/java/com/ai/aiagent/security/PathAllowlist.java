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
                    log.warn("Allowed root '{}' does not exist, ignoring it.", trimmed);
                }
            }
        }
        if (roots.isEmpty()) {
            log.warn("rag.ingestion.allowed-roots is empty, so /admin/ingest-folder will reject "
                    + "every path. Set RAG_ALLOWED_ROOTS to enable it.");
        } else {
            log.info("Folders allowed for server-side ingest: {}", roots);
        }
    }

    public boolean hasRoots() {
        return !roots.isEmpty();
    }

    public List<String> configuredRoots() {
        return roots.stream().map(Path::toString).toList();
    }

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

    public static String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) return "upload.bin";
        String name = Paths.get(original).getFileName().toString();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return "upload.bin";
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
