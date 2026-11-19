package com.pharma.inventory.repository;

import com.pharma.inventory.entity.StockTransferItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockTransferItemRepository extends JpaRepository<StockTransferItem, Long> {
    List<StockTransferItem> findByTransferId(Long transferId);

    List<StockTransferItem> findByTransferIdIn(List<Long> transferIds);
}
