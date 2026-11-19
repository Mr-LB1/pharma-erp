package com.pharma.inventory.dto;

import java.util.List;

public record StocktakeCreateRequest(Long warehouseId,
                                     List<StocktakeItemRequest> items) {
}
