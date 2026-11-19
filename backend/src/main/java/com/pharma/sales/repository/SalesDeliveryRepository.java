package com.pharma.sales.repository;

import com.pharma.sales.entity.SalesDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SalesDeliveryRepository extends JpaRepository<SalesDelivery, Long> {

    boolean existsByDeliveryNo(String deliveryNo);

    List<SalesDelivery> findByOrderId(Long orderId);

    @Query("SELECT d FROM SalesDelivery d WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR d.deliveryNo LIKE %:keyword%) AND " +
            "(:status IS NULL OR :status = '' OR d.status = :status)")
    Page<SalesDelivery> search(@Param("keyword") String keyword,
                               @Param("status") String status,
                               Pageable pageable);
}
