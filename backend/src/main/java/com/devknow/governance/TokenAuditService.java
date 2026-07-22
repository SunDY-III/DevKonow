package com.devknow.governance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Token 计费审计：
 * - 流式调用在 onComplete 回调里拿真实 TokenUsage 记账；
 * - Agent / Embedding 场景按内容长度估算（标注场景，方便区分精确值与估算值）；
 * - 异步落库不阻塞主链路；定时任务每日聚合出日报（实际可接邮件/钉钉）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenAuditService {

    private final TokenUsageLogRepository repository;

    /** 单用户每日 Token 预算上限（0=不限制） */
    @Value("${app.token-budget.daily-per-user:0}")
    private long dailyBudgetPerUser;

    @Async
    public void record(Long userId, String scene, Integer inputTokens, Integer outputTokens) {
        // 预算检查：超出上限则记录警告但不阻塞（仅提示，不中断业务流程）
        if (dailyBudgetPerUser > 0 && userId != null && userId > 0) {
            long todayUsage = repository.sumByUserSince(userId, LocalDate.now().atStartOfDay());
            if (todayUsage + inputTokens + outputTokens > dailyBudgetPerUser) {
                log.warn("Token 预算超限: userId={}, todayUsage={}, budget={}, new={}+{}",
                        userId, todayUsage, dailyBudgetPerUser, inputTokens, outputTokens);
            }
        }
        try {
            TokenUsageLog logRow = new TokenUsageLog();
            logRow.setUserId(userId == null ? 0L : userId);
            logRow.setScene(scene);
            logRow.setInputTokens(inputTokens == null ? 0 : inputTokens);
            logRow.setOutputTokens(outputTokens == null ? 0 : outputTokens);
            repository.save(logRow);
        } catch (Exception e) {
            log.warn("token audit failed (non-blocking)", e);   // 审计失败不影响业务
        }
    }

    /** 每日 1 点出前一天的 Token 消耗日报 */
    @Scheduled(cron = "0 0 1 * * ?")
    public void dailyReport() {
        LocalDateTime start = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime end = LocalDate.now().atStartOfDay();
        var rows = repository.aggregate(start, end);
        log.info("===== Token 日报 {} =====", start.toLocalDate());
        rows.forEach(r -> log.info("user={} scene={} in={} out={}",
                r.getUserId(), r.getScene(), r.getInputTokens(), r.getOutputTokens()));
    }
}
