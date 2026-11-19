package com.pharma.system.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 角色-权限映射（sys_role_permission）读写：表只是纯关联表，用 JdbcTemplate 直连更直观。
 */
@Repository
public class RolePermissionRepository {

    private final JdbcTemplate jdbcTemplate;

    public RolePermissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 角色当前拥有的权限码，按 code 升序。 */
    public List<String> findPermissionCodesByRoleId(Long roleId) {
        return jdbcTemplate.queryForList(
                "SELECT p.code FROM sys_permission p " +
                        "JOIN sys_role_permission rp ON p.id = rp.permission_id " +
                        "WHERE rp.role_id = ? ORDER BY p.code",
                String.class, roleId);
    }

    public void deleteByRoleId(Long roleId) {
        jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id = ?", roleId);
    }

    public void insert(Long roleId, Long permissionId) {
        jdbcTemplate.update("INSERT INTO sys_role_permission(role_id, permission_id) VALUES (?, ?)",
                roleId, permissionId);
    }

    /** 拥有某权限码的角色 id 列表（排查用）。 */
    public List<Long> findRoleIdsByPermissionCode(String code) {
        return jdbcTemplate.queryForList(
                "SELECT rp.role_id FROM sys_role_permission rp " +
                        "JOIN sys_permission p ON p.id = rp.permission_id " +
                        "WHERE p.code = ? ORDER BY rp.role_id",
                Long.class, code);
    }
}
