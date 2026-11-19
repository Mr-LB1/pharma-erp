package com.pharma.inventory.dto;

import java.time.LocalDateTime;
import java.util.List;

public record StocktakeVO(Long id,
                          String stocktakeNo,
                          Long warehouseId,
                          String status,
                          String createdBy,
                          LocalDateTime createdAt,
                          List<StocktakeItemVO> items) {
}
