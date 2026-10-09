package com.novelagent.modelaccess.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** The development actor is not authentication. Protect account-control operations separately. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ChatGptConnectionGuard extends OncePerRequestFilter {
    private final String key;
    public ChatGptConnectionGuard(@Value("${app.ai.chatgpt.connection-admin-key:}") String key) { this.key = key; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return "OPTIONS".equals(request.getMethod()) || !(path.equals("/api/v1/settings/model/chatgpt")
                || path.startsWith("/api/v1/settings/model/chatgpt/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String provided = request.getHeader("X-ChatGPT-Admin-Key");
        boolean valid = !key.isBlank() && provided != null && provided.length() <= 1024
                && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
        if (valid) { response.setHeader("Cache-Control", "no-store"); chain.doFilter(request, response); return; }
        response.setStatus(key.isBlank() ? 503 : 403);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(key.isBlank()
                ? "{\"message\":\"服务端未配置 ChatGPT 连接管理口令\"}"
                : "{\"message\":\"ChatGPT 连接管理口令不正确\"}");
    }
}
