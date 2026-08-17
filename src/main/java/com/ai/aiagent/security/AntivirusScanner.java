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

/**
 * Quet virus file duoc nap, qua giao thuc INSTREAM cua clamd.
 *
 * Vi sao noi giao thuc thay vi dung thu vien: giao thuc INSTREAM chi gom vai dong -
 * gui {@code zINSTREAM\0}, roi tung khoi {@code <do dai 4 byte big-endian><du lieu>},
 * ket thuc bang khoi do dai 0, doc lai mot dong ket qua. Them mot phu thuoc it duoc
 * cap nhat chi de lam viec nay la khong dang, cung ly do voi {@code GeminiLlmClient}.
 *
 * MAC DINH TAT de may dev khong phai dung ClamAV. Nhung khi da BAT ma khong ket noi
 * duoc toi clamd thi mac dinh TU CHOI nap file ({@code fail-closed}) - cung nguyen
 * tac voi {@code EntraScopeService} khi Graph loi: "khong kiem tra duoc" phai co
 * nghia la "khong cho qua". Mot bo quet virus tu dong bo qua khi no chet la mot bo
 * quet virus khong ton tai.
 */
@Component
@Slf4j
public class AntivirusScanner {

    /** clamd tu cat ket noi neu mot khoi lon hon StreamMaxLength (mac dinh 25MB). */
    private static final int CHUNK_SIZE = 32 * 1024;

    private final RagProperties props;

    public AntivirusScanner(RagProperties props) {
        this.props = props;
    }

    /**
     * @throws InfectedFileException khi phat hien ma doc, hoac khi khong quet duoc va
     *                               dang o che do {@code fail-closed}
     */
    public void scan(byte[] content, String fileName) {
        RagProperties.Antivirus config = props.getAntivirus();
        if (!config.isEnabled()) return;
        if (content == null || content.length == 0) return;

        String verdict;
        try {
            verdict = ask(content, config);
        } catch (IOException e) {
            log.error("Quet virus: khong ket noi duoc clamd {}:{} - {}",
                    config.getHost(), config.getPort(), e.getMessage());
            if (config.isFailClosed()) {
                throw new InfectedFileException("Khong quet duoc virus cho '" + fileName
                        + "' (khong ket noi duoc dich vu quet). File bi tu choi.");
            }
            return;
        }

        // clamd tra ve "stream: OK" hoac "stream: <ten ma doc> FOUND".
        if (verdict.endsWith("OK")) {
            log.debug("Quet virus: '{}' sach.", fileName);
            return;
        }
        if (verdict.contains("FOUND")) {
            String signature = verdict.replace("stream:", "").replace("FOUND", "").strip();
            log.error("Quet virus: '{}' NHIEM '{}' - da chan.", fileName, signature);
            throw new InfectedFileException("File '" + fileName + "' bi phat hien chua ma doc ("
                    + signature + ") nen khong duoc nap.");
        }

        log.error("Quet virus: clamd tra ve ket qua la '{}' cho '{}'.", verdict, fileName);
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
                    out.writeInt(length);           // big-endian, dung dinh dang clamd doi
                    out.write(content, offset, length);
                }
                out.writeInt(0);                    // khoi rong = het du lieu
                out.flush();

                byte[] buffer = new byte[512];
                int read = in.read(buffer);
                if (read <= 0) throw new IOException("clamd khong tra ve gi.");
                return new String(buffer, 0, read, StandardCharsets.UTF_8)
                        .replace("\0", "").strip();
            }
        }
    }

    /** Trang thai san sang, hien o {@code /admin/overview}. */
    public boolean isEnabled() {
        return props.getAntivirus().isEnabled();
    }

    /** Bat len la loi nap lieu, khong phai loi he thong - xem {@code ApiExceptionHandler}. */
    public static class InfectedFileException extends RuntimeException {
        public InfectedFileException(String message) {
            super(message);
        }
    }
}
