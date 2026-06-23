package io.github.zzz8688.diagagent.util;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class JwtContextUtil {

    private static final Long DEFAULT_USER_ID = 1L;
    private static final String DEFAULT_USERNAME = "diagagent";

    private JwtContextUtil() {
    }

    public static Long getCurrentUserId() {
        Claims claims = resolveClaims();
        if (claims == null) {
            return DEFAULT_USER_ID;
        }
        Object userId = claims.get("userId");
        if (userId instanceof Number number) {
            return number.longValue();
        }
        if (userId instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return DEFAULT_USER_ID;
    }

    public static String getCurrentUsername() {
        Claims claims = resolveClaims();
        if (claims == null) {
            return DEFAULT_USERNAME;
        }
        Object username = claims.get("username");
        return username == null ? DEFAULT_USERNAME : username.toString();
    }

    private static Claims resolveClaims() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return null;
        }
        HttpServletRequest request = servletRequestAttributes.getRequest();
        if (request == null) {
            return null;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        if (token.isBlank()) {
            return null;
        }
        try {
            return JwtUtil.parseToken(token);
        } catch (Exception ignored) {
            return null;
        }
    }
}
