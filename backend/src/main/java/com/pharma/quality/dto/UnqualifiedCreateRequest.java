package com.pharma.quality.dto;

import java.math.BigDecimal;

/**
 * 不合格品登记（P1-17）：warehouseId 必填，隔离扣减只允许发生在指定仓库。
 */
public record UnqualifiedCreateRequest(Long productId,
                                       Long warehouseId,
                                       String batchNo,
                                       BigDecimal quantity,
                                       String reason) {
}
