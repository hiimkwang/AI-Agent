package com.ai.aiagent.admin;

import com.ai.aiagent.store.UsageReportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bao cao van hanh theo bot (chi ADMIN).
 *
 * Cau hoi ma bao cao nay phai tra loi duoc, va truoc day khong tra loi duoc: bot nao
 * dang duoc dung, bot nao dang tu choi qua nhieu (thieu tai lieu), bot nao bi 👎 nhieu
 * (tra loi sai), bot nao dot tien nhat. So lieu gop toan he giau het nhung dieu do.
 */
@RestController
@RequestMapping("/api/v1/rag/admin/reports")
@Slf4j
public class ReportController {

    private final UsageReportRepository reports;

    public ReportController(UsageReportRepository reports) {
        this.reports = reports;
    }

    /** Bang tong hop theo bot + tong toan he trong cung cua so thoi gian. */
    @GetMapping("/bots")
    public Map<String, Object> byBot(@RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> bots = reports.byBot(days);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("bots", bots);
        out.put("total", totalOf(bots));
        return out;
    }

    /** Chuoi theo ngay, de ve do thi xu huong. */
    @GetMapping("/daily")
    public Map<String, Object> daily(@RequestParam(defaultValue = "14") int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("items", reports.daily(days));
        return out;
    }

    /**
     * Cau hoi bi tu choi nhieu nhat: danh sach TAI LIEU CAN NAP, xep theo so lan bi hoi.
     *
     * @param bot loc theo mot bot; de trong thi tinh toan he
     */
    @GetMapping("/gaps")
    public Map<String, Object> gaps(@RequestParam(required = false) String bot,
                                    @RequestParam(defaultValue = "30") int days,
                                    @RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bot", bot == null ? "" : bot);
        out.put("days", days);
        out.put("items", reports.topAbstained(bot, days, limit));
        return out;
    }

    /**
     * Cong don thu cong thay vi mot cau SQL rieng.
     *
     * Ty le duoc tinh lai tu tong, KHONG lay trung binh cac ty le cua tung bot: mot bot
     * tra loi 3 cau se keo trung binh cong lech han so voi thuc te.
     */
    private Map<String, Object> totalOf(List<Map<String, Object>> bots) {
        long questions = 0;
        long abstained = 0;
        long up = 0;
        long down = 0;
        double cost = 0;
        for (Map<String, Object> b : bots) {
            questions += (Long) b.get("questions");
            abstained += (Long) b.get("abstained");
            up += (Long) b.get("thumbsUp");
            down += (Long) b.get("thumbsDown");
            cost += (Double) b.get("costUsd");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bots", bots.size());
        out.put("questions", questions);
        out.put("abstained", abstained);
        out.put("abstainRate", questions == 0 ? null
                : Math.round(abstained * 1000.0 / questions) / 10.0);
        out.put("thumbsUp", up);
        out.put("thumbsDown", down);
        out.put("satisfactionRate", up + down == 0 ? null
                : Math.round(up * 1000.0 / (up + down)) / 10.0);
        out.put("costUsd", Math.round(cost * 1_000_000.0) / 1_000_000.0);
        return out;
    }
}
