package com.pharma.sales.repository;

import com.pharma.sales.entity.SalesReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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
}
