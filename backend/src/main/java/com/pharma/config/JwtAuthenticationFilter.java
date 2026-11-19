package com.pharma.config;

import com.pharma.system.entity.SysUser;
import com.pharma.system.repository.SysUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 解析 Authorization: Bearer <token>，校验后注入认证上下文。
 * 除签名/过期外，还校验：
 * <ul>
 *   <li>jti 是否在 Redis 黑名单中（批次十一，单 token 注销；Redis 不可用时降级放行）</li>
 *   <li>账号启用状态与 tokenVersion（禁用、改密、登出兜底时整批失效，P1-24）</li>
 * </ul>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;
    private final SysUserRepository userRepository;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   CustomUserDetailsService userDetailsService,
                                   SysUserRepository userRepository,
                                   TokenBlacklistService tokenBlacklistService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                if (jwtUtil.isValid(token)
                        && SecurityContextHolder.getContext().getAuthentication() == null
                        // jti 命中黑名单说明该 token 已被单独注销：不注入认证，交由 entry point 返回 401
                        && !tokenBlacklistService.isBlacklisted(jwtUtil.getJti(token))) {
                    String username = jwtUtil.getUsername(token);
                    int tokenVersion = jwtUtil.getTokenVersion(token);
                    SysUser user = userRepository.findByUsername(username).orElse(null);
                    if (user != null && tokenVersionMatches(user, tokenVersion)) {
                        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            } catch (Exception ignored) {
                // token 无效、用户不存在或版本过期：不注入认证，交由 entry point 返回 401
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 账号必须启用，且 token 版本与库内一致（改密/禁用/登出后版本已 +1）。 */
    private boolean tokenVersionMatches(SysUser user, int tokenVersion) {
        if (!"ACTIVE".equals(user.getStatus())) {
            return false;
        }
        int current = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        return current == tokenVersion;
    }
}
