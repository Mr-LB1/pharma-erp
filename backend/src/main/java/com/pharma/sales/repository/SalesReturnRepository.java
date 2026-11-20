package com.pharma.sales.repository;

import com.pharma.sales.entity.SalesReturn;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SalesReturnRepository extends JpaRepository<SalesReturn, Long> {

    List<SalesReturn> findByDeliveryId(Long deliveryId);

    List<SalesReturn> findByStatusOrderByIdDesc(String status);

    @Query("select r from SalesReturn r where (:keyword is null or r.returnNo like %:keyword%) " +
            "and (:status is null or r.status = :status) " +
            "and (:createdBy is null or r.createdBy = :createdBy)")
    Page<SalesReturn> search(@Param("keyword") String keyword,
                             @Param("status") String status,
                             @Param("createdBy") String createdBy,
                             Pageable pageable);

    /**
     * P0-2 单据头行级悲观锁：提交 / 验收 / 作废前先锁退货单头，
     * 避免并发重复验收重复回补库存与重复生成红字应收，或重复作废覆盖状态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SalesReturn r WHERE r.id = :id")
    Optional<SalesReturn> findByIdForUpdate(@Param("id") Long id);
}
