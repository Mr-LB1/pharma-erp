package com.pharma.system;

import com.pharma.config.JwtUtil;
import com.pharma.config.TokenBlacklistService;
import com.pharma.system.dto.CreateUserRequest;
import com.pharma.system.dto.LoginRequest;
import com.pharma.system.service.AuthService;
import com.pharma.system.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次十一：Redis JWT jti 单 token 黑名单（真实连接本地 Redis 的集成用例）。
 * <p>Redis 未启动时用例自动跳过；降级路径见 {@link TokenBlacklistDegradationTest}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TokenBlacklistTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private TokenBlacklistService tokenBlacklistService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 本次用例写入的 jti，@AfterEach 清理，避免黑名单 key 在本地 Redis 中残留 */
    private final List<String> touchedJti = new ArrayList<>();

    @BeforeEach
    void requireRedis() {
        boolean up;
        try {
            up = redisTemplate.hasKey("jwt:blacklist:__probe__") != null;
        } catch (Exception e) {
            up = false;
        }
        assumeTrue(up, "本地 Redis 未启动，跳过真实 Redis 集成用例");
    }

    @AfterEach
    void cleanUp() {
        touchedJti.forEach(tokenBlacklistService::remove);
        touchedJti.clear();
    }

    @Test
    @DisplayName("登出只失效当前 token：该 token 401，同用户另一个 token 仍 200")
    void logoutOnlyInvalidatesCurrentToken() throws Exception {
        String username = "jti-" + System.nanoTime();
        String password = "pwd12345";
        as("boss", () -> userService.create(
                new CreateUserRequest(username, password, "单token注销用户", null, List.of("SALES"))));

        String tokenA = login(username, password);
        String tokenB = login(username, password);
        String jtiA = jwtUtil.getJti(tokenA);
        String jtiB = jwtUtil.getJti(tokenB);
        touchedJti.add(jtiA);
        touchedJti.add(jtiB);

        assertThat(jtiA).isNotBlank().isNotEqualTo(jtiB);
        assertThatMeOk(tokenA);
        assertThatMeOk(tokenB);

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // 只拉黑 jtiA，jtiB 不受影响
        assertThat(tokenBlacklistService.isBlacklisted(jtiA)).isTrue();
        assertThat(tokenBlacklistService.isBlacklisted(jtiB)).isFalse();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        assertThatMeOk(tokenB);
    }

    @Test
    @DisplayName("黑名单 key 带 TTL，且不超过 token 剩余有效期")
    void blacklistKeyHasTtl() {
        String username = "jti-ttl-" + System.nanoTime();
        String password = "pwd12345";
        as("boss", () -> userService.create(
                new CreateUserRequest(username, password, "TTL 用户", null, List.of("SALES"))));

        String token = login(username, password);
        String jti = jwtUtil.getJti(token);
        touchedJti.add(jti);

        assertThat(tokenBlacklistService.blacklist(jti, jwtUtil.remainingTtl(token))).isTrue();

        Long ttlSeconds = redisTemplate.getExpire("jwt:blacklist:" + jti);
        assertThat(ttlSeconds).isNotNull().isPositive();
        assertThat(ttlSeconds).isLessThanOrEqualTo(jwtUtil.remainingTtl(token).toSeconds() + 1);
    }

    private String login(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return authService.login(request).getToken();
    }

    private void assertThatMeOk(String token) throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").isNotEmpty());
    }

    private <T> T as(String username, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
