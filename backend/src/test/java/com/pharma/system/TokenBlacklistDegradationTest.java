package com.pharma.system;

import com.pharma.config.TokenBlacklistService;
import com.pharma.system.dto.CreateUserRequest;
import com.pharma.system.dto.LoginRequest;
import com.pharma.system.service.AuthService;
import com.pharma.system.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次十一降级用例：Redis 不可用不得阻断登录/业务请求，登出退回 tokenVersion 整批失效。
 * 用 {@link MockBean} 覆盖 StringRedisTemplate 模拟连接故障（无需真实 Redis）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TokenBlacklistDegradationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private TokenBlacklistService tokenBlacklistService;
    @MockBean
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void simulateRedisDown() {
        RedisConnectionFailureException down = new RedisConnectionFailureException("simulated: redis down");
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        doThrow(down).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(redisTemplate.hasKey(anyString())).thenThrow(down);
        when(redisTemplate.getExpire(anyString())).thenThrow(down);
        when(redisTemplate.delete(anyString())).thenThrow(down);
    }

    @Test
    @DisplayName("Redis 故障：黑名单查询降级放行，登录与受保护接口照常 200")
    void redisDownDoesNotBlockRequests() throws Exception {
        assertThat(tokenBlacklistService.isBlacklisted("any-jti")).isFalse();
        assertThat(tokenBlacklistService.blacklist("any-jti", Duration.ofMinutes(5))).isFalse();

        String username = "jti-down-" + System.nanoTime();
        String password = "pwd12345";
        as("boss", () -> userService.create(
                new CreateUserRequest(username, password, "降级用户", null, List.of("SALES"))));

        String token = login(username, password);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));
    }

    @Test
    @DisplayName("Redis 故障：登出退回 tokenVersion，当前 token 仍立即 401")
    void logoutFallsBackToTokenVersionWhenRedisDown() throws Exception {
        String username = "jti-down-out-" + System.nanoTime();
        String password = "pwd12345";
        as("boss", () -> userService.create(
                new CreateUserRequest(username, password, "降级登出用户", null, List.of("SALES"))));

        String token = login(username, password);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private String login(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return authService.login(request).getToken();
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
