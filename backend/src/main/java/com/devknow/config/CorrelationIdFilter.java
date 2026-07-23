package com.devknow.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级 correlationId 注入 —— 贯穿日志链路。
 *
 * <p>每个 HTTP 请求分配唯一 traceId（未传入时自动生成），
 * 注入 MDC 供 logback JSON 日志输出使用。
 * 前端可通过 {@code X-Trace-Id} 请求头传入自己的 traceId 以便前后端关联。
 *
 * <p>在 Logstash JSON 日志中自动携带 {@code traceId} 字段，
 * 通过 grep "traceId":"xxx" 即可过滤出整条请求链路的所有日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        try {
            HttpServletRequest httpReq = (HttpServletRequest) request;
            String traceId = httpReq.getHeader(TRACE_HEADER);

            // 前端未传入时自动生成
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            }

            MDC.put(MDC_KEY, traceId);

            // 写回响应头，方便前端/frontend 调试关联
            if (response instanceof HttpServletResponse httpResp) {
                httpResp.setHeader(TRACE_HEADER, traceId);
            }

            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
