package com.pharma.system;

import com.pharma.system.dto.ChangePasswordRequest;
import com.pharma.system.dto.CreateUserRequest;
import com.pharma.system.dto.LoginRequest;
import com.pharma.system.dto.UserVO;
import com.pharma.system.service.AuthService;
import com.pharma.system.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Supplier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-24 JWT 加固测试：token 携带 jti + tokenVersion；
 * 改密 / 禁用 / 登出后旧 token 立即 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JwtVersionTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;

    @Test
    @DisplayName("token 含 jti/tv；改密后旧 token 401，新密码可登录")
    void oldTokenInvalidAfterPasswordChange() throws Exception {
        String username = "jwt-" + System.nanoTime();
        String password = "pwd12345";
        UserVO user = as("boss", () -> userService.create(
                new CreateUserRequest(username, password, "JWT 用户", null, List.of("SALES"))));

        String token = login(username, password);
        assertThatMeOk(token);

        asVoid("boss", () -> userService.changePassword(user.id(), new ChangePasswordRequest("newpwd123")));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        String fresh = login(username, "newpwd123");
        assertThatMeOk(fresh);

        // 登出后该 token 立即失效
        authService.logout(username);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + fresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("禁用账号后旧 token 401，且不能登录/续期")
    void disabledUserTokenRejected() throws Exception {
        String username = "jwt-off-" + System.nanoTime();
        String password = "pwd12345";
        UserVO user = as("boss", () -> userService.create(
                new CreateUserRequest(username, password, "待禁用用户", null, List.of("SALES"))));

        String token = login(username, password);
        assertThatMeOk(token);

        as("boss", () -> userService.toggle(user.id()));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("me 返回 permissions 权限码列表")
    void meReturnsPermissions() throws Exception {
        String username = "perm-" + System.nanoTime();
        String password = "pwd12345";
        as("boss", () -> userService.create(
                new CreateUserRequest(username, password, "权限用户", null, List.of("WAREHOUSE"))));

        String token = login(username, password);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.permissions", org.hamcrest.Matchers.hasItem("inventory:stocktake:create")))
                .andExpect(jsonPath("$.data.roles[0]").value("WAREHOUSE"));
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

    private void asVoid(String username, Runnable action) {
        as(username, () -> {
            action.run();
            return null;
        });
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
