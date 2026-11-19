package com.pharma.inventory.repository;

import com.pharma.inventory.entity.LotTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LotTraceRepository extends JpaRepository<LotTrace, Long> {

    List<LotTrace> findByProductIdAndBatchNoOrderByIdAsc(Long productId, String batchNo);

    /** 入库追溯：PURCHASE_RECEIVE_ITEM -> STOCK */
    Optional<LotTrace> findFirstByProductIdAndBatchNoAndSourceTypeAndSourceIdAndTargetType(
            Long productId, String batchNo, String sourceType, Long sourceId, String targetType);

    /** 出库追溯：STOCK -> SALES_DELIVERY_ITEM */
    Optional<LotTrace> findFirstByProductIdAndBatchNoAndTargetTypeAndTargetIdAndSourceType(
            Long productId, String batchNo, String targetType, Long targetId, String sourceType);
}
