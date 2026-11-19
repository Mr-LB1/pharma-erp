package com.pharma.common.importer;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 导入权限校验：{resource}:create（资源名→权限前缀见 {@link ResourcePrefixes}）。
 * 用法：@PreAuthorize("@importPermission.check(#resource)")。
 */
@Component("importPermission")
public class ImportPermissionChecker {

    public boolean check(String resource) {
        if (resource == null) {
            return false;
        }
        String code = ResourcePrefixes.of(resource) + ":create";
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }
}
