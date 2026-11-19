package com.pharma.system.controller;

import com.pharma.common.ApiResponse;
import com.pharma.system.dto.UpdateRolePermissionsRequest;
import com.pharma.system.entity.SysRole;
import com.pharma.system.service.RoleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色查询与角色权限配置。
 */
@RestController
@RequestMapping("/api/system/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:role:view')")
    public ApiResponse<List<SysRole>> list() {
        return ApiResponse.ok(roleService.list());
    }

    /** 角色现有权限码列表。 */
    @GetMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('system:role:view')")
    public ApiResponse<List<String>> permissions(@PathVariable Long id) {
        return ApiResponse.ok(roleService.getPermissions(id));
    }

    /** 全量覆盖角色权限，返回更新后的权限码列表。 */
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('system:role:config')")
    public ApiResponse<List<String>> updatePermissions(@PathVariable Long id,
                                                       @Valid @RequestBody UpdateRolePermissionsRequest request) {
        return ApiResponse.ok(roleService.updatePermissions(id, request.permissionCodes()));
    }
}
