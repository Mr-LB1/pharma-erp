package com.pharma.sales.repository;

import com.pharma.sales.entity.SalesOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SalesOrderItemRepository extends JpaRepository<SalesOrderItem, Long> {

    List<SalesOrderItem> findByOrderId(Long orderId);

    List<SalesOrderItem> findByOrderIdIn(Collection<Long> orderIds);

    boolean existsByProductId(Long productId);
}
