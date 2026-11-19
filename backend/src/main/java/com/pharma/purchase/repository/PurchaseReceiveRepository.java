package com.pharma.purchase.repository;

import com.pharma.purchase.entity.PurchaseReceive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PurchaseReceiveRepository extends JpaRepository<PurchaseReceive, Long> {

    boolean existsByReceiveNo(String receiveNo);

    List<PurchaseReceive> findByOrderId(Long orderId);

    @Query("SELECT r FROM PurchaseReceive r WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR r.receiveNo LIKE %:keyword%) AND " +
            "(:status IS NULL OR :status = '' OR r.status = :status)")
    Page<PurchaseReceive> search(@Param("keyword") String keyword,
                                 @Param("status") String status,
                                 Pageable pageable);
}
