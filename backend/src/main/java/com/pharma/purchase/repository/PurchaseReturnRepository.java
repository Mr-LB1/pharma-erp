package com.pharma.purchase.repository;

import com.pharma.purchase.entity.PurchaseReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
