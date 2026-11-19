package com.pharma.finance.repository;

import com.pharma.finance.entity.Receipt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

    @Query("select r from Receipt r where (:customerId is null or r.customerId = :customerId) " +
            "and (:keyword is null or r.receiptNo like %:keyword%)")
    Page<Receipt> search(@Param("customerId") Long customerId,
                         @Param("keyword") String keyword,
                         Pageable pageable);
}
