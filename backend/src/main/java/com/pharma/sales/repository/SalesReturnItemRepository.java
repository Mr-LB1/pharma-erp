package com.pharma.sales.repository;

import com.pharma.sales.entity.SalesReturnItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SalesReturnItemRepository extends JpaRepository<SalesReturnItem, Long> {
    List<SalesReturnItem> findByReturnId(Long returnId);

    List<SalesReturnItem> findByReturnIdIn(List<Long> returnIds);
}
