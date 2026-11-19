package com.pharma.system.service;

import com.pharma.common.BusinessException;
import com.pharma.config.JwtUtil;
import com.pharma.config.TokenBlacklistService;
import com.pharma.system.dto.LoginRequest;
import com.pharma.system.dto.LoginResponse;
import com.pharma.system.dto.UserInfo;
import com.pharma.system.entity.SysUser;
import com.pharma.system.repository.SysPermissionRepository;
import com.pharma.system.repository.SysRoleRepository;
import com.pharma.system.repository.SysUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * 认证服务：登录校验、当前用户信息（角色 + 权限码）、token 续期与注销。
 * token 携带 jti 与 tokenVersion：
 * 登出按 jti 单 token 拉黑（Redis），禁用/改密仍用 tokenVersion 整批失效（P1-24 / 批次十一）。
 */
@Service
public class AuthService {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthService(SysUserRepository userRepository,
                       SysRoleRepository roleRepository,
                       SysPermissionRepository permissionRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       TokenBlacklistService tokenBlacklistService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    public LoginResponse login(LoginRequest request) {
        SysUser user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> BusinessException.unauthorized("用户名或密码错误"));
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordBcrypt())) {
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw BusinessException.unauthorized("账号已被禁用");
        }
        return issueToken(user);
    }

    /**
     * 续期：凭有效的当前登录身份换发新 token（新 jti，沿用当前 tokenVersion）。
     * 账号被禁用或不存在时拒绝。
     */
    public LoginResponse refresh(String username) {
        SysUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> BusinessException.unauthorized("用户不存在"));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw BusinessException.unauthorized("账号已被禁用");
        }
        return issueToken(user);
    }

    /**
     * 注销（整批兜底）：tokenVersion +1，当前及此前签发的所有 token 立即失效。
     * 仅在拿不到当前 token 的 jti 时使用。
     */
    @Transactional
    public void logout(String username) {
        userRepository.findByUsername(username).ifPresent(this::revokeTokens);
    }

    /**
     * 注销（单 token）：把当前 token 的 jti 写入 Redis 黑名单，只让这一个 token 失效，
     * 同用户其它设备的 token 继续可用（批次十一）。
     * <p>降级：拿不到 jti 或 Redis 写入失败时，退回 {@link #revokeTokens} 整批失效，保证"至少当前 token 失效"。
     */
    @Transactional
    public void logout(String username, String rawToken) {
        String jti = jwtUtil.getJtiQuietly(rawToken);
        Duration ttl = jwtUtil.remainingTtl(rawToken);
        if (!tokenBlacklistService.blacklist(jti, ttl)) {
            userRepository.findByUsername(username).ifPresent(this::revokeTokens);
        }
    }

    public UserInfo currentUser(String username) {
        SysUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> BusinessException.unauthorized("用户不存在"));
        return toUserInfo(user);
    }

    /** 使用户全部已签发 token 失效（禁用/改密/登出复用）。 */
    @Transactional
    public void revokeTokens(SysUser user) {
        int current = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        user.setTokenVersion(current + 1);
        userRepository.save(user);
    }

    private LoginResponse issueToken(SysUser user) {
        List<String> roles = roleRepository.findRoleCodesByUserId(user.getId());
        int version = user.getTokenVersion() == null ? 1 : user.getTokenVersion();
        String token = jwtUtil.generateToken(user.getUsername(), roles, version);
        return new LoginResponse(token, toUserInfo(user));
    }

    private UserInfo toUserInfo(SysUser user) {
        List<String> roles = roleRepository.findRoleCodesByUserId(user.getId());
        List<String> permissions = permissionRepository.findPermissionCodesByUserId(user.getId());
        return new UserInfo(user.getId(), user.getUsername(), user.getRealName(), roles, permissions);
    }
}
