package com.pharma.common;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据权限工具（P1-23）：
 * - 订单列表：ADMIN 或具备 approval:view 的用户可看全部，其余仅看自己创建的单据；
 * - 成本脱敏：具备 inventory:cost:view（或 ADMIN）才可见 unitCost。
 */
public final class DataScope {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String APPROVAL_VIEW = "approval:view";
    public static final String COST_VIEW = "inventory:cost:view";

    private DataScope() {
    }

    /** 订单可见范围：返回 null 表示不限（ADMIN / approval:view），否则为当前用户名。 */
    public static String orderScopeUser() {
        if (hasAuthority(ROLE_ADMIN) || hasAuthority(APPROVAL_VIEW)) {
            return null;
        }
        return SecurityUtils.currentUser();
    }

    public static boolean canViewCost() {
        return hasAuthority(ROLE_ADMIN) || hasAuthority(COST_VIEW);
    }

    /** 是否为数据全量可见的 privileged 身份（ADMIN / approval:view）。 */
    public static boolean isPrivileged() {
        return hasAuthority(ROLE_ADMIN) || hasAuthority(APPROVAL_VIEW);
    }

    /**
     * 单据归属校验（IDOR 防护）：非 ADMIN / approval:view 只能操作自己创建的单据。
     * createdBy 为空视为历史数据，同样拒绝非特权用户。
     */
    public static void requireOwnership(String createdBy) {
        requireAnyOwnership(createdBy);
    }

    /** 单据归属校验：createdBy 命中任意一个即通过（出入库按自身或关联订单的制单人判定）。 */
    public static void requireAnyOwnership(String... createdBys) {
        if (isPrivileged()) {
            return;
        }
        String current = SecurityUtils.currentUser();
        for (String owner : createdBys) {
            if (owner != null && owner.equals(current)) {
                return;
            }
        }
        throw BusinessException.forbidden("无权操作他人单据");
    }

    public static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities() == null) {
            return false;
        }
        Set<String> codes = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return codes.contains(authority);
    }

    /**
     * 是否存在已认证的当前用户（匿名 / 无安全上下文视为系统内部调用）。
     * 待审列表按审批人角色过滤时以此为开关：无真实用户时不收窄，保持内部调用与测试兼容。
     */
    public static boolean hasCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return false;
        }
        return auth.getName() != null && !auth.getName().isBlank()
                && !"anonymousUser".equals(auth.getName());
    }

    /** 当前用户持有的角色码（去掉 ROLE_ 前缀，与 approval_config.approver_role 同口径）。 */
    public static Set<String> currentRoleCodes() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.toSet());
    }
}
