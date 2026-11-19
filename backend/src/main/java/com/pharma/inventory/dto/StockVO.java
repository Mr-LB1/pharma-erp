package com.pharma.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 批次库存返回：无 inventory:cost:view 权限时 unitCost 置 null（P1-23 成本脱敏）；
 * productName 为冗余名称，避免前端仅凭 ID 显示（P1-30）。
 */
public record StockVO(Long id,
                      Long productId,
                      String productName,
                      String batchNo,
                      LocalDate produceDate,
                      LocalDate expireDate,
                      Long locationId,
                      BigDecimal quantity,
                      BigDecimal unitCost) {
}
