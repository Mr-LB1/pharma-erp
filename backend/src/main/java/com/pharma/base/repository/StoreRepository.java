package com.pharma.base.repository;

import com.pharma.base.entity.Store;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    boolean existsByWarehouseId(Long warehouseId);

    Optional<Store> findByCode(String code);

    /** 列表状态过滤：默认 ACTIVE；可查 INACTIVE 恢复启用；ALL 查全部 */
    @Query("SELECT s FROM Store s WHERE (:status = 'ALL' OR s.status = :status) AND " +
            "(:keyword IS NULL OR :keyword = '' OR " +
            "s.code LIKE %:keyword% OR s.name LIKE %:keyword%)")
    Page<Store> search(@Param("keyword") String keyword,
                       @Param("status") String status,
                       Pageable pageable);

    /** 证照到期预警：license_expire <= deadline（含已过期），按到期日升序，排除已停用门店。 */
    @Query("SELECT s FROM Store s WHERE s.status = 'ACTIVE' AND s.licenseExpire IS NOT NULL " +
            "AND s.licenseExpire <= :deadline ORDER BY s.licenseExpire ASC")
    List<Store> findExpiringLicenses(@Param("deadline") LocalDate deadline);
}
