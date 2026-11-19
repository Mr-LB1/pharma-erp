package com.pharma.base.repository;

import com.pharma.base.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsByCode(String code);

    Optional<Product> findByCode(String code);

    /** 列表状态过滤：默认 ACTIVE；可查 INACTIVE 恢复启用；ALL 查全部 */
    @Query("SELECT p FROM Product p WHERE (:status = 'ALL' OR p.status = :status) AND " +
            "(:keyword IS NULL OR :keyword = '' OR " +
            "p.code LIKE %:keyword% OR p.genericName LIKE %:keyword% OR " +
            "p.tradeName LIKE %:keyword% OR p.approvalNo LIKE %:keyword% OR p.manufacturer LIKE %:keyword%)")
    Page<Product> search(@Param("keyword") String keyword,
                         @Param("status") String status,
                         Pageable pageable);

    Page<Product> findByFirstAuditStatusAndStatus(String firstAuditStatus, String status, Pageable pageable);
}
