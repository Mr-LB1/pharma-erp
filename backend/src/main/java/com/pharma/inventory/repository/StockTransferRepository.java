package com.pharma.inventory.repository;

import com.pharma.inventory.entity.StockTransfer;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    @Query("select t from StockTransfer t where (:keyword is null or t.transferNo like %:keyword%) " +
            "and (:createdBy is null or t.createdBy = :createdBy) " +
            "and (:status is null or :status = '' or t.status = :status)")
    Page<StockTransfer> search(@Param("keyword") String keyword,
                               @Param("createdBy") String createdBy,
                               @Param("status") String status,
                               Pageable pageable);

    /**
     * 单据头行级悲观锁：submit / reject / approveDocument / wmsCallback / executeActual / void 入口统一加锁，
     * 防止并发重复审批、重复执行，以及作废与执行交错（同一事务内重复锁读同一行不会重复加锁）。
     *
     * @param id 调拨单主键
     * @return 已加锁的调拨单（不存在时为空）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM StockTransfer t WHERE t.id = :id")
    Optional<StockTransfer> findByIdForUpdate(@Param("id") Long id);
}
