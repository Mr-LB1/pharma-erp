package com.pharma.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT 生成与解析（HS256）。
 * claims：sub=用户名、roles=角色编码、jti=令牌唯一号、tv=用户 token 版本号（P1-24）。
 */
@Component
public class JwtUtil {

    private static final String CLAIM_TOKEN_VERSION = "tv";

    private final SecretKey key;
    private final long expiration;

    public JwtUtil(@Value("${app.jwt.secret}") String secret,
                   @Value("${app.jwt.expiration}") long expiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    public String generateToken(String username, List<String> roles, int tokenVersion) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("roles", roles)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public String getUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public String getJti(String token) {
        return parseClaims(token).getId();
    }

    /** token 中的版本号；老 token 无该 claim 时按 0 处理（必然与库内 >=1 不匹配，自然失效）。 */
    public int getTokenVersion(String token) {
        Object tv = parseClaims(token).get(CLAIM_TOKEN_VERSION);
        return tv instanceof Number n ? n.intValue() : 0;
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 解析 jti，解析失败返回 null（不抛异常，供黑名单降级路径使用）。
     */
    public String getJtiQuietly(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return parseClaims(token).getId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * token 剩余有效期（from exp）。已过期或解析失败返回 {@link Duration#ZERO}，
     * 供黑名单设置 TTL 使用。
     */
    public Duration remainingTtl(String token) {
        if (token == null || token.isBlank()) {
            return Duration.ZERO;
        }
        try {
            Date exp = parseClaims(token).getExpiration();
            if (exp == null) {
                return Duration.ZERO;
            }
            long millis = exp.getTime() - System.currentTimeMillis();
            return millis > 0 ? Duration.ofMillis(millis) : Duration.ZERO;
        } catch (Exception e) {
            return Duration.ZERO;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
