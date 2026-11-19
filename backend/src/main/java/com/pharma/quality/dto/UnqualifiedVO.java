package com.pharma.quality.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record UnqualifiedVO(Long id,
                            Long productId,
                            String batchNo,
                            BigDecimal quantity,
                            String reason,
                            String status,
                            String createdBy,
                            LocalDateTime createdAt,
                            String genericName,
                            String tradeName,
                            String spec,
                            Long warehouseId,
                            String warehouseName,
                            String disposeMethod,
                            LocalDateTime disposedAt,
                            String disposedBy) {
}
