package com.pharma.finance.repository;

import com.pharma.finance.entity.ArInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ArInvoiceRepository extends JpaRepository<ArInvoice, Long> {

    List<ArInvoice> findByBizTypeAndBizId(String bizType, Long bizId);

    List<ArInvoice> findByBizTypeAndBizIdAndStatusNot(String bizType, Long bizId, String status);

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
