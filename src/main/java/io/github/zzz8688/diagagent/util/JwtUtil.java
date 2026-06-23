package io.github.zzz8688.diagagent.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public final class JwtUtil {

    private static final String SECRET = "diagagent-jwt-secret-key-for-local-runtime-2026";
    private static final long EXPIRE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtUtil() {
    }

    public static String getToken(Long userId, String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claim("userId", userId)
                .claim("username", username)
                .setSubject(username == null ? "diagagent" : username)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + EXPIRE_MS))
                .signWith(KEY, SignatureAlgorithm.HS256)
                .compact();
    }

    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
