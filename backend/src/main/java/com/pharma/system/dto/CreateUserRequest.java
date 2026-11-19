package com.pharma.system.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建用户：用户名/初始密码必填，角色可选（编码列表）。
 * roleCodes 兼容前端字段名 roles。
 */
public record CreateUserRequest(@NotBlank(message = "用户名不能为空")
                                @Size(max = 50, message = "用户名不能超过50个字符")
                                String username,
                                @NotBlank(message = "密码不能为空")
                                @Size(min = 6, max = 64, message = "密码长度需在6~64个字符之间")
                                String password,
                                @Size(max = 50, message = "姓名不能超过50个字符")
                                String realName,
                                String status,
                                @JsonAlias("roles")
                                List<String> roleCodes) {
}
