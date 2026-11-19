package com.pharma.sales.dto;

import java.math.BigDecimal;

public record SalesDeliveryItemVO(Long id,
                                  Long productId,
                                  String productName,
                                  String batchNo,
                                  BigDecimal quantity,
                                  BigDecimal unitCost,
                                  BigDecimal actualQty,
                                  BigDecimal qtyDiff) {
}
