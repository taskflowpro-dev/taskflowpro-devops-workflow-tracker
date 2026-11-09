package com.taskflow.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Per-instance resource protection; deploy a shared edge limiter for a cluster. */
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private record Window(long minute, int count) { }
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/") || request.getMethod().equals("OPTIONS")) { chain.doFilter(request, response); return; }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String identity = authentication instanceof JwtAuthenticationToken token ? "u:" + token.getToken().getSubject() : "ip:" + request.getRemoteAddr();
        boolean expensive = !request.getMethod().equals("GET") && (request.getRequestURI().contains("/intelligence/") || request.getRequestURI().contains("/repositories/"));
        int limit = expensive ? 20 : 180;
        String key = identity + (expensive ? ":compute" : ":api");
        long minute = System.currentTimeMillis() / 60000;
        if (windows.size() > 10000) windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
        if (windows.size() > 20000 && !windows.containsKey(key)) {
            response.setStatus(429); response.setHeader("Retry-After", "60"); return;
        }
        Window window = windows.compute(key, (ignored, previous) -> previous == null || previous.minute() != minute ? new Window(minute, 1) : new Window(minute, previous.count() + 1));
        if (window.count() > limit) {
            response.setStatus(429); response.setHeader("Retry-After", "60"); response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many requests. Please wait a minute and retry.\"}"); return;
        }
        chain.doFilter(request, response);
    }
}
