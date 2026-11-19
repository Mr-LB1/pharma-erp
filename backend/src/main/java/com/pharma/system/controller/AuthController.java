package com.pharma.system.controller;

import com.pharma.common.ApiResponse;
import com.pharma.system.dto.LoginRequest;
import com.pharma.system.dto.LoginResponse;
import com.pharma.system.dto.UserInfo;
import com.pharma.system.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录 / 当前用户 / 续期 / 注销。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserInfo> me(Authentication authentication) {
        return ApiResponse.ok(authService.currentUser(authentication.getName()));
    }

    /** 续期：需携带有效的 Authorization token。 */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(Authentication authentication) {
        return ApiResponse.ok(authService.refresh(authentication.getName()));
    }

    /**
     * 注销：把当前 token 的 jti 写入 Redis 黑名单，只让这一个 token 失效；
     * 同用户其它 token 继续可用。拿不到 jti / Redis 不可用时退回 tokenVersion 整批失效。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(Authentication authentication,
                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(authentication.getName(), extractToken(authorization));
        return ApiResponse.ok(null);
    }

    private String extractToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7);
    }
}
