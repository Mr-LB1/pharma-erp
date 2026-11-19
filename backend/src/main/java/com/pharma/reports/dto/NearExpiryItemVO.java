package com.pharma.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 近效期清单（批次 + 效期 + 剩余天数）。
 */
public record NearExpiryItemVO(Long productId,
                               String productCode,
                               String productName,
                               String genericName,
                               String tradeName,
                               String spec,
                               String manufacturer,
                               String batchNo,
                               LocalDate expireDate,
                               LocalDate produceDate,
                               BigDecimal quantity,
                               long daysLeft) {
}
