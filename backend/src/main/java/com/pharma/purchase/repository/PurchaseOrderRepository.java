package com.pharma.purchase.repository;

import com.pharma.purchase.entity.PurchaseOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    boolean existsByOrderNo(String orderNo);

    /**
     * 关键字搜索（#37 多字段）：命中单号或供应商名。供应商为 Long 外键（无关联映射），用 EXISTS 子查询匹配名称。
     */
    @Query("SELECT o FROM PurchaseOrder o WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR o.orderNo LIKE %:keyword% " +
            " OR EXISTS (SELECT 1 FROM Supplier s WHERE s.id = o.supplierId AND s.name LIKE %:keyword%)) " +
            "AND (:status IS NULL OR :status = '' OR o.status = :status) " +
            "AND (:createdBy IS NULL OR o.createdBy = :createdBy)")
    Page<PurchaseOrder> search(@Param("keyword") String keyword,
                               @Param("status") String status,
                               @Param("createdBy") String createdBy,
                               Pageable pageable);

    List<PurchaseOrder> findByStatusOrderByIdDesc(String status);

    boolean existsBySupplierId(Long supplierId);
}
