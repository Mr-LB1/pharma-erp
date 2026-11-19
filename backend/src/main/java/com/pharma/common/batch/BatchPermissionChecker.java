package com.pharma.common.batch;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 批量操作权限校验：按 action 动态映射到 {prefix}:create / :update / :delete。
 * 在 @PreAuthorize 中配合使用：@PreAuthorize("@batchPermission.check('product', #request)")。
 */
@Component("batchPermission")
public class BatchPermissionChecker {

    public boolean check(String prefix, BatchRequest<?> request) {
        if (prefix == null || request == null || request.getAction() == null) {
            return false;
        }
        return has(prefix + ":" + request.getAction().permissionSuffix());
    }

    /** 直接按权限码判断（批量作废等按固定权限码逐项校验的场景）。 */
    public boolean has(String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> permissionCode.equals(a.getAuthority()));
    }
}
