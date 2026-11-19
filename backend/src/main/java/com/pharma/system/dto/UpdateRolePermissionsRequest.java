package com.pharma.system.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 角色权限配置：全量覆盖（空列表表示清空该角色所有权限）。
 */
public record UpdateRolePermissionsRequest(@NotNull(message = "权限码列表不能为空")
                                           List<String> permissionCodes) {
}
