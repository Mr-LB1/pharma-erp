package com.pharma.finance.repository;

import com.pharma.finance.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query("select p from Payment p where (:supplierId is null or p.supplierId = :supplierId) " +
            "and (:keyword is null or p.paymentNo like %:keyword%)")
    Page<Payment> search(@Param("supplierId") Long supplierId,
                         @Param("keyword") String keyword,
                         Pageable pageable);
}
