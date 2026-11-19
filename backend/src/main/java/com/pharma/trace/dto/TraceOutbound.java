package com.pharma.trace.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 出库追溯链路：批次 → 销售出库明细 → 销售出库单 → 销售订单 → 客户。
 * deliveryStatus 为出库单状态（ACTIVE/VOID 等），VOID 行保留由前端灰显（#18）。
 */
public record TraceOutbound(Long deliveryItemId,
                            Long deliveryId,
                            String deliveryNo,
                            LocalDate deliveryDate,
                            BigDecimal quantity,
                            BigDecimal unitCost,
                            Long orderId,
                            String orderNo,
                            Long customerId,
                            String customerName,
                            String deliveryStatus) {
}
