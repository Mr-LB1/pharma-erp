package com.pharma.system.controller;

import com.pharma.common.ApiResponse;
import com.pharma.system.entity.SysPermission;
import com.pharma.system.service.RoleService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限码字典：角色权限配置面板的勾选项来源，返回 [{id, code, name}]，按 code 升序。
 */
@RestController
@RequestMapping("/api/system/permissions")
public class PermissionController {

    private final RoleService roleService;

    public PermissionController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:role:view')")
    public ApiResponse<List<SysPermission>> list() {
        return ApiResponse.ok(roleService.listAllPermissions());
    }
}
