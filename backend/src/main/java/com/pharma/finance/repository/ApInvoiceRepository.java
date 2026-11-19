package com.pharma.finance.repository;

import com.pharma.finance.entity.ApInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ApInvoiceRepository extends JpaRepository<ApInvoice, Long> {

    List<ApInvoice> findByBizTypeAndBizId(String bizType, Long bizId);

    List<ApInvoice> findByBizTypeAndBizIdAndStatusNot(String bizType, Long bizId, String status);

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
