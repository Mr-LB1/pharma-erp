package com.pharma.finance.repository;

import com.pharma.finance.entity.ArInvoice;
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

public interface ArInvoiceRepository extends JpaRepository<ArInvoice, Long> {

    List<ArInvoice> findByBizTypeAndBizId(String bizType, Long bizId);

    List<ArInvoice> findByBizTypeAndBizIdAndStatusNot(String bizType, Long bizId, String status);

    /**
     * 按主键加行级悲观写锁读取应收单：收款核销前锁定目标应收单并重读已核销金额，防止并发重复核销。
     *
     * @param id 应收单主键，不可为空
     * @return 已加锁的应收单，不存在时返回空
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ArInvoice a where a.id = :id")
    Optional<ArInvoice> findByIdForUpdate(@Param("id") Long id);

    @Query("select a from ArInvoice a where (:customerId is null or a.customerId = :customerId) " +
            "and (:status is null or a.status = :status) " +
            "and (:keyword is null or a.invoiceNo like %:keyword%)")
    Page<ArInvoice> search(@Param("customerId") Long customerId,
                           @Param("status") String status,
                           @Param("keyword") String keyword,
                           Pageable pageable);

    /** 客户未结应收合计（不含 VOID/SETTLED）。 */
    @Query("select coalesce(sum(a.invoiceAmount - a.settledAmount), 0) from ArInvoice a " +
            "where a.customerId = :customerId and a.status in ('UNSETTLED','PARTIAL')")
    BigDecimal sumOutstandingByCustomer(@Param("customerId") Long customerId);

    @Query("select a from ArInvoice a where a.customerId = :customerId and a.status in ('UNSETTLED','PARTIAL')")
    List<ArInvoice> findOutstandingByCustomer(@Param("customerId") Long customerId);
}
