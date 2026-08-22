package com.ai.aiagent.security;

import com.ai.aiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class AntivirusScanner {

    private static final int CHUNK_SIZE = 32 * 1024;

    private final RagProperties props;

    public AntivirusScanner(RagProperties props) {
        this.props = props;
    }

    public void scan(byte[] content, String fileName) {
        RagProperties.Antivirus config = props.getAntivirus();
        if (!config.isEnabled()) return;
        if (content == null || content.length == 0) return;

        String verdict;
        try {
            verdict = ask(content, config);
        } catch (IOException e) {
            log.error("Cannot reach clamd at {}:{} - {}",
                    config.getHost(), config.getPort(), e.getMessage());
            if (config.isFailClosed()) {
                throw new InfectedFileException("Khong quet duoc virus cho '" + fileName
                        + "' (khong ket noi duoc dich vu quet). File bi tu choi.");
            }
            return;
        }

        if (verdict.endsWith("OK")) {
            log.debug("Antivirus scan clean: '{}'.", fileName);
            return;
        }
        if (verdict.contains("FOUND")) {
            String signature = verdict.replace("stream:", "").replace("FOUND", "").strip();
            log.error("Antivirus scan: '{}' is INFECTED with '{}', ingest blocked.",
                    fileName, signature);
            throw new InfectedFileException("File '" + fileName + "' bi phat hien chua ma doc ("
                    + signature + ") nen khong duoc nap.");
        }

        log.error("Antivirus scan: clamd returned an unexpected verdict '{}' for '{}'.",
                    verdict, fileName);
        if (config.isFailClosed()) {
            throw new InfectedFileException("Ket qua quet virus khong doc duoc cho '"
                    + fileName + "'. File bi tu choi.");
        }
    }

    private String ask(byte[] content, RagProperties.Antivirus config) throws IOException {
        try (Socket socket = new Socket()) {
            int timeoutMs = Math.max(1, config.getTimeoutSeconds()) * 1000;
            socket.connect(new InetSocketAddress(config.getHost(), config.getPort()), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 InputStream in = socket.getInputStream()) {

                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                for (int offset = 0; offset < content.length; offset += CHUNK_SIZE) {
                    int length = Math.min(CHUNK_SIZE, content.length - offset);
                    out.writeInt(length);
                    out.write(content, offset, length);
                }
                out.writeInt(0);
                out.flush();

                byte[] buffer = new byte[512];
                int read = in.read(buffer);
                if (read <= 0) throw new IOException("clamd khong tra ve gi.");
                return new String(buffer, 0, read, StandardCharsets.UTF_8)
                        .replace("\0", "").strip();
            }
        }
    }

    public boolean isEnabled() {
        return props.getAntivirus().isEnabled();
    }

    public static class InfectedFileException extends RuntimeException {
        public InfectedFileException(String message) {
            super(message);
        }
    }
}
