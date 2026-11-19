package com.pharma.purchase.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PurchaseReceiveVO(Long id,
                                String receiveNo,
                                Long orderId,
                                String orderNo,
                                LocalDate receiveDate,
                                String status,
                                String supplierName,
                                String createdBy,
                                LocalDateTime createdAt,
                                String wmsNo,
                                LocalDateTime wmsPushedAt,
                                LocalDateTime wmsCallbackAt,
                                List<PurchaseReceiveItemVO> items) {
}
