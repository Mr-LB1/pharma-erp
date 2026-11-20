package com.pharma.sales.repository;

import com.pharma.sales.entity.SalesDelivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SalesDeliveryRepository extends JpaRepository<SalesDelivery, Long> {

    boolean existsByDeliveryNo(String deliveryNo);

    List<SalesDelivery> findByOrderId(Long orderId);

    @Query("SELECT d FROM SalesDelivery d WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR d.deliveryNo LIKE %:keyword%) AND " +
            "(:status IS NULL OR :status = '' OR d.status = :status)")
    Page<SalesDelivery> search(@Param("keyword") String keyword,
                               @Param("status") String status,
                               Pageable pageable);

    /**
     * P0-2 单据头行级悲观锁：提交 / 审批 / WMS 回传 / 作废前先锁出库单头，
     * 避免并发重复提交、重复回传、重复作废互相覆盖状态与库存。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM SalesDelivery d WHERE d.id = :id")
    Optional<SalesDelivery> findByIdForUpdate(@Param("id") Long id);
}
