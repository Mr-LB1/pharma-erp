package com.pharma.sales.repository;

import com.pharma.sales.entity.SalesDeliveryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SalesDeliveryItemRepository extends JpaRepository<SalesDeliveryItem, Long> {

    List<SalesDeliveryItem> findByDeliveryId(Long deliveryId);

    List<SalesDeliveryItem> findByDeliveryIdIn(Collection<Long> deliveryIds);

    boolean existsByProductId(Long productId);
}
