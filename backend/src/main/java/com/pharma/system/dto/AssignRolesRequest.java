package com.pharma.system.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 角色分配：全量覆盖（空列表表示清除所有角色）；兼容前端字段名 roles。
 */
public record AssignRolesRequest(@NotNull(message = "角色列表不能为空")
                                 @JsonAlias("roles")
                                 List<String> roleCodes) {
}
