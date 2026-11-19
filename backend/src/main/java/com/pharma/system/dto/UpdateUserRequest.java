package com.pharma.system.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * 编辑用户基本信息（用户名不可改；启停建议走 toggle 接口）。
 * roleCodes 非空时按全量覆盖分配角色（兼容前端字段名 roles）。
 */
public record UpdateUserRequest(String realName,
                                String status,
                                @JsonAlias("roles")
                                List<String> roleCodes) {
}
