package com.pharma.inventory.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 仓库调拨单视图。 */
public record StockTransferVO(Long id,
                              String transferNo,
                              Long fromWarehouseId,
                              String fromWarehouseName,
                              Long toWarehouseId,
                              String toWarehouseName,
                              LocalDate transferDate,
                              String status,
                              String remark,
                              String createdBy,
                              LocalDateTime createdAt,
                              String wmsNo,
                              LocalDateTime wmsPushedAt,
                              LocalDateTime wmsCallbackAt,
                              List<StockTransferItemVO> items) {
}
