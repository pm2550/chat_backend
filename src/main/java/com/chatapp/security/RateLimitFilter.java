package com.chatapp.security;

import com.chatapp.config.RateLimitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitConfig rateLimitConfig;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientIp = getClientIp(request);
        String path = request.getRequestURI();

        Bucket bucket;
        if (path.startsWith("/api/auth/")) {
            bucket = rateLimitConfig.resolveAuthBucket(clientIp);
        } else {
            bucket = rateLimitConfig.resolveBucket(clientIp);
        }

        if (!bucket.tryConsume(1)) {
            writeRateLimited(response, null);
            return;
        }

        if ("GET".equalsIgnoreCase(request.getMethod())
                && "/api/auth/client-salt-params".equals(path)) {
            String username = request.getParameter("username");
            if (username != null && !username.isBlank()
                    && !rateLimitConfig.resolveSaltLookupBucket(username).tryConsume(1)) {
                writeRateLimited(response, "3600");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void writeRateLimited(HttpServletResponse response, String retryAfter) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        if (retryAfter != null) {
            response.setHeader("Retry-After", retryAfter);
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Map.of("code", 429, "message", "请求过于频繁，请稍后重试", "timestamp", System.currentTimeMillis()));
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}
