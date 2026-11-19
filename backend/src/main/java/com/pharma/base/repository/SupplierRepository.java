package com.pharma.base.repository;

import com.pharma.base.entity.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    boolean existsByName(String name);

    Optional<Supplier> findFirstByNameOrderByIdAsc(String name);

    /** 列表状态过滤：默认 ACTIVE；可查 INACTIVE 恢复启用；ALL 查全部 */
    @Query("SELECT s FROM Supplier s WHERE (:status = 'ALL' OR s.status = :status) AND " +
            "(:keyword IS NULL OR :keyword = '' OR " +
            "s.name LIKE %:keyword% OR s.creditCode LIKE %:keyword% OR " +
            "s.licenseNo LIKE %:keyword% OR s.gspCert LIKE %:keyword%)")
    Page<Supplier> search(@Param("keyword") String keyword,
                          @Param("status") String status,
                          Pageable pageable);

    Page<Supplier> findByFirstAuditStatusAndStatus(String firstAuditStatus, String status, Pageable pageable);
}
