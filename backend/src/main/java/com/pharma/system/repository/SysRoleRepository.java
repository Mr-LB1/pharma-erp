package com.pharma.system.repository;

import com.pharma.system.entity.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SysRoleRepository extends JpaRepository<SysRole, Long> {

    boolean existsByCode(String code);

    Optional<SysRole> findByCode(String code);

    List<SysRole> findByCodeIn(Collection<String> codes);

    @Query(value = "SELECT r.code FROM sys_role r JOIN sys_user_role ur ON r.id = ur.role_id WHERE ur.user_id = :userId ORDER BY r.code",
            nativeQuery = true)
    List<String> findRoleCodesByUserId(@Param("userId") Long userId);
}
