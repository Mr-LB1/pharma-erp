package com.pharma.inventory.repository;

import com.pharma.inventory.entity.StockTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockTransactionRepository extends JpaRepository<StockTransaction, Long> {

    List<StockTransaction> findByRefTypeAndRefId(String refType, Long refId);
}
