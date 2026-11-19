package com.pharma.sales.repository;

import com.pharma.sales.entity.SalesOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    boolean existsByOrderNo(String orderNo);

    /**
     * 关键字搜索（#37 多字段）：命中单号或客户名。客户为 Long 外键（无关联映射），用 EXISTS 子查询匹配名称。
     */
    @Query("SELECT o FROM SalesOrder o WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR o.orderNo LIKE %:keyword% " +
            " OR EXISTS (SELECT 1 FROM Customer c WHERE c.id = o.customerId AND c.name LIKE %:keyword%)) " +
            "AND (:status IS NULL OR :status = '' OR o.status = :status) " +
            "AND (:createdBy IS NULL OR o.createdBy = :createdBy)")
    Page<SalesOrder> search(@Param("keyword") String keyword,
                            @Param("status") String status,
                            @Param("createdBy") String createdBy,
                            Pageable pageable);

    List<SalesOrder> findByStatusOrderByIdDesc(String status);

    boolean existsByCustomerId(Long customerId);
}
