package com.pharma.purchase.repository;

import com.pharma.purchase.entity.PurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    List<PurchaseOrderItem> findByOrderId(Long orderId);

    List<PurchaseOrderItem> findByOrderIdIn(Collection<Long> orderIds);

    boolean existsByProductId(Long productId);
}
