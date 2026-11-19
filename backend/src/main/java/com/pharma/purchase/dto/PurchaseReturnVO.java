package com.pharma.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 采购退货单视图。 */
public record PurchaseReturnVO(Long id,
                               String returnNo,
                               Long receiveId,
                               String receiveNo,
                               Long supplierId,
                               String supplierName,
                               LocalDate returnDate,
                               String status,
                               BigDecimal totalAmount,
                               String reason,
                               String createdBy,
                               LocalDateTime createdAt,
                               String wmsNo,
                               LocalDateTime wmsPushedAt,
                               LocalDateTime wmsCallbackAt,
                               List<PurchaseReturnItemVO> items) {
}
