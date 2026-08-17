package com.ai.aiagent.retention;

import com.ai.aiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Xem va chay tay chinh sach luu tru (chi ADMIN).
 *
 * Co duong chay tay khong phai de thay the lich - ma de KIEM CHUNG duoc rang lich
 * dang lam dung viec, ma khong phai doi den 2 gio sang.
 */
@RestController
@RequestMapping("/api/v1/rag/admin")
@Slf4j
public class RetentionController {

    private final RetentionService retention;
    private final RagProperties props;

    public RetentionController(RetentionService retention, RagProperties props) {
        this.retention = retention;
        this.props = props;
    }

    @GetMapping("/retention")
    public Map<String, Object> status() {
        RagProperties.Retention config = props.getRetention();
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("enabled", config.isEnabled());
        policy.put("runAtHour", config.getRunAtHour());
        policy.put("conversationDays", config.getConversationDays());
        policy.put("auditDays", config.getAuditDays());
        policy.put("jobDays", config.getJobDays());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy", policy);
        out.put("lastRun", retention.lastRun());
        out.put("note", "Gia tri <= 0 nghia la giu vinh vien nhom du lieu do.");
        return out;
    }

    @PostMapping("/retention/run")
    public Map<String, Object> runNow() {
        log.info("Don du lieu qua han: chay tay theo yeu cau tu man quan tri.");
        return retention.run();
    }
}
