package com.pharma.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StocktakeItemRequest(Long productId,
                                   String batchNo,
                                   BigDecimal actualQty,
                                   LocalDate produceDate,
                                   LocalDate expireDate) {
}
