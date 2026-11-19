package com.pharma.system.repository;

import com.pharma.system.entity.SysPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SysPermissionRepository extends JpaRepository<SysPermission, Long> {

    /**
     * 按用户展开其所有角色对应的权限码（供 granted authorities 使用）。
     */
    @Query(value = "SELECT DISTINCT p.code FROM sys_permission p " +
            "JOIN sys_role_permission rp ON p.id = rp.permission_id " +
            "JOIN sys_user_role ur ON rp.role_id = ur.role_id " +
            "WHERE ur.user_id = :userId ORDER BY p.code", nativeQuery = true)
    List<String> findPermissionCodesByUserId(@Param("userId") Long userId);
}
