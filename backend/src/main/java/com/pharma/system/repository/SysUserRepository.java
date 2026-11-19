package com.pharma.system.repository;

import com.pharma.system.entity.SysUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 用户管理列表：用户名/姓名模糊搜索。 */
    @Query("SELECT u FROM SysUser u WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR u.username LIKE %:keyword% OR u.realName LIKE %:keyword%)")
    Page<SysUser> search(@Param("keyword") String keyword, Pageable pageable);

    /** 指定角色的启用账号数（用于「最后一个管理员」保护）。 */
    @Query(value = "SELECT COUNT(DISTINCT u.id) FROM sys_user u " +
            "JOIN sys_user_role ur ON ur.user_id = u.id " +
            "JOIN sys_role r ON r.id = ur.role_id " +
            "WHERE r.code = :roleCode AND u.status = 'ACTIVE'", nativeQuery = true)
    long countActiveByRoleCode(@Param("roleCode") String roleCode);

    /** 该用户是否拥有指定角色。 */
    @Query(value = "SELECT COUNT(*) FROM sys_user_role ur " +
            "JOIN sys_role r ON r.id = ur.role_id " +
            "WHERE ur.user_id = :userId AND r.code = :roleCode", nativeQuery = true)
    long countByUserIdAndRoleCode(@Param("userId") Long userId, @Param("roleCode") String roleCode);
}
