package com.pharma.finance.repository;

import com.pharma.finance.entity.ApInvoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ApInvoiceRepository extends JpaRepository<ApInvoice, Long> {

    List<ApInvoice> findByBizTypeAndBizId(String bizType, Long bizId);

    List<ApInvoice> findByBizTypeAndBizIdAndStatusNot(String bizType, Long bizId, String status);

    /**
     * 按主键加行级悲观写锁读取应付单：付款核销前锁定目标应付单并重读已核销金额，防止并发重复核销。
     *
     * @param id 应付单主键，不可为空
     * @return 已加锁的应付单，不存在时返回空
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApInvoice a where a.id = :id")
    Optional<ApInvoice> findByIdForUpdate(@Param("id") Long id);

    @Query("select a from ApInvoice a where (:supplierId is null or a.supplierId = :supplierId) " +
            "and (:status is null or a.status = :status) " +
            "and (:keyword is null or a.invoiceNo like %:keyword%)")
    Page<ApInvoice> search(@Param("supplierId") Long supplierId,
                           @Param("status") String status,
                           @Param("keyword") String keyword,
                           Pageable pageable);

    @Query("select coalesce(sum(a.invoiceAmount - a.settledAmount), 0) from ApInvoice a " +
            "where a.supplierId = :supplierId and a.status in ('UNSETTLED','PARTIAL')")
    BigDecimal sumOutstandingBySupplier(@Param("supplierId") Long supplierId);

    @Query("select a from ApInvoice a where a.supplierId = :supplierId and a.status in ('UNSETTLED','PARTIAL')")
    List<ApInvoice> findOutstandingBySupplier(@Param("supplierId") Long supplierId);
}
