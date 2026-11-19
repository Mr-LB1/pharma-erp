package com.pharma.base.repository;

import com.pharma.base.entity.Warehouse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    boolean existsByName(String name);

    Optional<Warehouse> findFirstByNameOrderByIdAsc(String name);

    /** 列表状态过滤：默认 ACTIVE；可查 INACTIVE 恢复启用；ALL 查全部 */
    @Query("SELECT w FROM Warehouse w WHERE (:status = 'ALL' OR w.status = :status) AND " +
            "(:keyword IS NULL OR :keyword = '' OR " +
            "w.name LIKE %:keyword% OR w.tempType LIKE %:keyword% OR w.status LIKE %:keyword%)")
    Page<Warehouse> search(@Param("keyword") String keyword,
                           @Param("status") String status,
                           Pageable pageable);
}
