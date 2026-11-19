package com.pharma.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 库存余额（分页）。
 */
public record InventoryBalanceVO(Long productId,
                                 String productCode,
                                 String productName,
                                 String genericName,
                                 String tradeName,
                                 String spec,
                                 String manufacturer,
                                 String batchNo,
                                 LocalDate expireDate,
                                 Long locationId,
                                 String warehouseName,
                                 BigDecimal quantity,
                                 BigDecimal unitCost) {
}
