package com.pharma.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StocktakeItemVO(Long id,
                              Long productId,
                              String batchNo,
                              BigDecimal bookQty,
                              BigDecimal actualQty,
                              BigDecimal diffQty,
                              LocalDate produceDate,
                              LocalDate expireDate) {
}
