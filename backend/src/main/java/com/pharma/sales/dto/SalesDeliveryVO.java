package com.pharma.sales.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record SalesDeliveryVO(Long id,
                              String deliveryNo,
                              Long orderId,
                              String orderNo,
                              LocalDate deliveryDate,
                              String status,
                              String customerName,
                              String createdBy,
                              LocalDateTime createdAt,
                              String wmsNo,
                              LocalDateTime wmsPushedAt,
                              LocalDateTime wmsCallbackAt,
                              List<SalesDeliveryItemVO> items) {
}
