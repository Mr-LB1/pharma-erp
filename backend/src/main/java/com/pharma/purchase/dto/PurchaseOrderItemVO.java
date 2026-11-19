package com.pharma.purchase.dto;

import java.math.BigDecimal;

public record PurchaseOrderItemVO(Long id,
                                  Long productId,
                                  String productName,
                                  BigDecimal quantity,
                                  BigDecimal unitPrice,
                                  BigDecimal amount) {
}
