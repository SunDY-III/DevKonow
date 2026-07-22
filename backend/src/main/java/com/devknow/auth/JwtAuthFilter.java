package com.devknow.auth;

import com.devknow.common.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器 —— 替代旧 JwtInterceptor。
 *
 * <p>从 Authorization: Bearer 请求头或 token query 参数提取 JWT，
 * 验证后设置 SecurityContextHolder（供 Spring Security 方法级鉴权使用）
 * 以及 UserContext ThreadLocal（供现有业务代码使用）。
 *
 * <p>兼容 SSE：EventSource 无法设置 Authorization 请求头，支持从 query 参数读取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (token != null) {
                Long userId = jwtUtil.parseUserId(token);
                String role = jwtUtil.parseRole(token);

                // 设置 ThreadLocal（兼容现有业务代码）
                UserContext.set(userId);

                // 设置 SecurityContext（供 Spring Security 鉴权）
                List<SimpleGrantedAuthority> authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + role)
                );
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception e) {
            // Token 无效或过期：不清除已有认证，让 SecurityConfig 的认证入口点处理
            SecurityContextHolder.clearContext();
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 清理 ThreadLocal 防止线程池复用导致用户串号
            UserContext.clear();
        }
    }

    /**
     * 从请求中提取 JWT Token。
     * 优先 Authorization 头，SSE 场景降级到 query 参数。
     */
    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return request.getParameter("token");
    }
}
