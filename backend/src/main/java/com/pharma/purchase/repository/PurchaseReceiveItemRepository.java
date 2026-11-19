package com.pharma.purchase.repository;

import com.pharma.purchase.entity.PurchaseReceiveItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PurchaseReceiveItemRepository extends JpaRepository<PurchaseReceiveItem, Long> {

    List<PurchaseReceiveItem> findByReceiveId(Long receiveId);

    List<PurchaseReceiveItem> findByReceiveIdIn(Collection<Long> receiveIds);

    boolean existsByProductId(Long productId);
}
