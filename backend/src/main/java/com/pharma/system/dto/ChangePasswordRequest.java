package com.pharma.system.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员重置/修改用户密码（兼容前端字段名 password）。
 */
public record ChangePasswordRequest(@NotBlank(message = "新密码不能为空")
                                    @Size(min = 6, max = 64, message = "密码长度需在6~64个字符之间")
                                    @JsonAlias("password")
                                    String newPassword) {
}
