package com.chatapp.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtils {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpirationMs;

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpirationMs;

    private SecretKey getSigningKey() {
        byte[] keyBytes = Base64.getDecoder().decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(String username) {
        return buildToken(username, accessTokenExpirationMs, "access");
    }

    public String generateRefreshToken(String username) {
        return buildToken(username, refreshTokenExpirationMs, "refresh");
    }

    private String buildToken(String username, long expirationMs, String tokenType) {
        return Jwts.builder()
                .setSubject(username)
                .setId(UUID.randomUUID().toString())
                .claim("type", tokenType)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    public String getUserNameFromJwtToken(String token) {
        return parseClaims(token).getSubject();
    }

    public String getTokenId(String token) {
        return parseClaims(token).getId();
    }

    public String getTokenType(String token) {
        return parseClaims(token).get("type", String.class);
    }

    public Date getExpirationDateFromJwtToken(String token) {
        return parseClaims(token).getExpiration();
    }

    public long getRemainingExpirationMs(String token) {
        Date expiration = getExpirationDateFromJwtToken(token);
        return Math.max(0, expiration.getTime() - System.currentTimeMillis());
    }

    public boolean validateJwtToken(String authToken) {
        try {
            parseClaims(authToken);
            return true;
        } catch (MalformedJwtException e) {
            logger.error("JWT令牌格式无效: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            logger.error("JWT令牌已过期: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            logger.error("不支持的JWT令牌: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("JWT声明字符串为空: {}", e.getMessage());
        }
        return false;
    }

    public boolean isTokenExpiringSoon(String token) {
        Date expiration = getExpirationDateFromJwtToken(token);
        long timeUntilExpiration = expiration.getTime() - System.currentTimeMillis();
        return timeUntilExpiration < 300000; // 5 minutes
    }

    public String refreshAccessToken(String refreshToken) {
        if (!validateJwtToken(refreshToken)) {
            throw new RuntimeException("无效的刷新令牌");
        }
        String tokenType = getTokenType(refreshToken);
        if (!"refresh".equals(tokenType)) {
            throw new RuntimeException("提供的不是刷新令牌");
        }
        String username = getUserNameFromJwtToken(refreshToken);
        return generateAccessToken(username);
    }

    // Legacy compatibility
    public String generateJwtToken(String username) {
        return generateAccessToken(username);
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
