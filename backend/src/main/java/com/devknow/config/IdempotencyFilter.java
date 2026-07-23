package com.devknow.config;

import com.devknow.common.IdempotencyService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Idempotency-Key 幂等过滤器。
 *
 * <p>对 {@code POST / PUT / PATCH / DELETE} 请求检查 {@code Idempotency-Key} 请求头：
 * <ul>
 *   <li>首次请求：正常放行，由 Controller 执行后在响应头中设置 {@code X-Idempotency-Completed}</li>
 *   <li>重复请求：直接返回缓存的处理结果（HTTP 409? 或 200 + 缓存结果）</li>
 * </ul>
 *
 * <p>注意：此过滤器不拦截所有请求，仅拦截需要幂等保护的写操作。
 * 具体的幂等性通过 {@link IdempotencyService} 在 Controller/Service 中显式调用。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // 在 CorrelationIdFilter 之后
public class IdempotencyFilter implements Filter {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String IDEMPOTENCY_RESULT_ATTR = "idempotency.result";

    private final IdempotencyService idempotencyService;

    public IdempotencyFilter(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                          FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;
        String method = httpReq.getMethod();

        // 仅对写操作检查幂等性
        if (!"POST".equals(method) && !"PUT".equals(method)
                && !"PATCH".equals(method) && !"DELETE".equals(method)) {
            chain.doFilter(request, response);
            return;
        }

        String idempotencyKey = httpReq.getHeader(IDEMPOTENCY_HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 未传入 Idempotency-Key：正常放行（不强制幂等）
            chain.doFilter(request, response);
            return;
        }

        // 检查是否已处理过
        String cachedResult = idempotencyService.getResult(idempotencyKey);
        if (cachedResult != null) {
            log.info("Idempotency-Key 命中: key={}, 返回缓存结果", idempotencyKey);
            httpResp.setHeader("X-Idempotency-Result", "cached");
            httpResp.setContentType("application/json;charset=UTF-8");
            httpResp.getWriter().write(cachedResult);
            return;
        }

        // 尝试获取幂等锁
        boolean acquired = idempotencyService.tryAcquire(idempotencyKey);
        if (!acquired) {
            // 有并发请求在处理中
            log.warn("Idempotency-Key 冲突: key={}, 当前有请求在处理", idempotencyKey);
            httpResp.setStatus(409);
            httpResp.setContentType("application/json;charset=UTF-8");
            httpResp.getWriter().write(
                    "{\"type\":\"https://developer.devknow.app/errors#409\","
                    + "\"title\":\"Conflict\",\"status\":409,"
                    + "\"detail\":\"当前请求正在处理中，请勿重复提交\"}");
            return;
        }

        // 首次请求：正常放行
        try {
            chain.doFilter(request, response);
        } finally {
            // 注意：此处不自动 complete，由 Controller/Service 在成功后显式调用
            // Filter 无法获取响应体，因此 complete 需在业务代码中完成
        }
    }
}
