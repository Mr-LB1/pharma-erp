package com.pharma.system.dto;

import java.util.List;

/**
 * 用户列表/详情返回（不含密码）。
 */
public record UserVO(Long id,
                     String username,
                     String realName,
                     String status,
                     List<String> roles) {
}
