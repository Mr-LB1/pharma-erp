package com.pharma.purchase.repository;

import com.pharma.purchase.entity.PurchaseReceive;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PurchaseReceiveRepository extends JpaRepository<PurchaseReceive, Long> {

    boolean existsByReceiveNo(String receiveNo);

    List<PurchaseReceive> findByOrderId(Long orderId);

    @Query("SELECT r FROM PurchaseReceive r WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR r.receiveNo LIKE %:keyword%) AND " +
            "(:status IS NULL OR :status = '' OR r.status = :status)")
    Page<PurchaseReceive> search(@Param("keyword") String keyword,
                                 @Param("status") String status,
                                 Pageable pageable);

    /**
     * 单据头行级悲观锁：submit / reject / approveDocument / wmsCallback / executeActual / void 入口统一加锁，
     * 防止并发重复审批、重复执行，以及作废与执行交错（同一事务内重复锁读同一行不会重复加锁）。
     *
     * @param id 采购入库单主键
     * @return 已加锁的入库单（不存在时为空）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PurchaseReceive r WHERE r.id = :id")
    Optional<PurchaseReceive> findByIdForUpdate(@Param("id") Long id);
}
