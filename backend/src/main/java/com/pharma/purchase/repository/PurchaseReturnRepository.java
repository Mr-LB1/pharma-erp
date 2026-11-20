package com.pharma.purchase.repository;

import com.pharma.purchase.entity.PurchaseReturn;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PurchaseReturnRepository extends JpaRepository<PurchaseReturn, Long> {

    @Query("select r from PurchaseReturn r where (:keyword is null or r.returnNo like %:keyword%) " +
            "and (:supplierId is null or r.supplierId = :supplierId) " +
            "and (:createdBy is null or r.createdBy = :createdBy) " +
            "and (:status is null or :status = '' or r.status = :status)")
    Page<PurchaseReturn> search(@Param("keyword") String keyword,
                                @Param("supplierId") Long supplierId,
                                @Param("createdBy") String createdBy,
                                @Param("status") String status,
                                Pageable pageable);

    /**
     * 单据头行级悲观锁：submit / reject / approveDocument / wmsCallback / executeActual / void 入口统一加锁，
     * 防止并发重复审批、重复执行，以及作废与执行交错（同一事务内重复锁读同一行不会重复加锁）。
     *
     * @param id 采购退货单主键
     * @return 已加锁的退货单（不存在时为空）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PurchaseReturn r WHERE r.id = :id")
    Optional<PurchaseReturn> findByIdForUpdate(@Param("id") Long id);
}
