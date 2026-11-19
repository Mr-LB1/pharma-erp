package com.pharma.trace.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 入库追溯链路：批次 ← 采购入库明细 ← 采购入库单 ← 采购订单 ← 供应商。
 * receiveStatus 为入库单状态（ACTIVE/VOID 等），VOID 行保留由前端灰显（#18）。
 */
public record TraceInbound(Long receiveItemId,
                           Long receiveId,
                           String receiveNo,
                           LocalDate receiveDate,
                           BigDecimal quantity,
                           LocalDate produceDate,
                           LocalDate expireDate,
                           BigDecimal unitCost,
                           String inspectionNo,
                           Long orderId,
                           String orderNo,
                           Long supplierId,
                           String supplierName,
                           String receiveStatus) {
}
