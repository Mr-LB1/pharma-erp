package com.pharma.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * JWT 单 token 黑名单（批次十一）。
 * <p>key = {@code jwt:blacklist:{jti}}，value = {@code 1}，TTL = token 剩余有效期（过期即自动清理）。
 * <p><b>降级策略</b>：Redis 任何异常都不向外抛。写入失败返回 false（由调用方决定是否回退整批失效），
 * 查询失败返回 false（放行，不因 Redis 故障阻断业务请求）。
 */
@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    private static final String KEY_PREFIX = "jwt:blacklist:";
    /** 无法确定剩余有效期时使用的兜底 TTL，避免产生永不过期的 key */
    private static final Duration MIN_TTL = Duration.ofSeconds(1);

    private final StringRedisTemplate redisTemplate;

    public TokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 拉黑一个 jti。
     *
     * @param ttl token 剩余有效期；为 null / 非正数时按 1 秒写入（token 本身已过期，无需长期占用）
     * @return true 表示已成功写入 Redis；false 表示 jti 为空或 Redis 不可用（已记录 warn，不抛异常）
     */
    public boolean blacklist(String jti, Duration ttl) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        Duration effective = (ttl == null || ttl.isZero() || ttl.isNegative()) ? MIN_TTL : ttl;
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", effective);
            return true;
        } catch (Exception e) {
            log.warn("JWT 黑名单写入失败（Redis 不可用，降级为不拉黑）jti={}: {}", jti, e.getMessage());
            return false;
        }
    }

    /**
     * 判断 jti 是否已被拉黑。
     *
     * @return 命中返回 true；jti 为空或 Redis 不可用时返回 false（降级放行）
     */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            log.warn("JWT 黑名单查询失败（Redis 不可用，降级放行）jti={}: {}", jti, e.getMessage());
            return false;
        }
    }

    /** 移除黑名单条目（测试/运维用）。失败静默。 */
    public void remove(String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + jti);
        } catch (Exception e) {
            log.warn("JWT 黑名单清理失败 jti={}: {}", jti, e.getMessage());
        }
    }
}
