package com.pharma.purchase.repository;

import com.pharma.purchase.entity.PurchaseReturnItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PurchaseReturnItemRepository extends JpaRepository<PurchaseReturnItem, Long> {
    List<PurchaseReturnItem> findByReturnId(Long returnId);

    List<PurchaseReturnItem> findByReturnIdIn(List<Long> returnIds);
}
