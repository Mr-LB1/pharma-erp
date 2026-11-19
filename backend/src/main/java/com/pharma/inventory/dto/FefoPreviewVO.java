package com.pharma.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * #13 FEFO 事前批次预览（只读，不含 unitCost，避免成本脱敏越权）。
 * blocked=true 时前端应禁用保存；blockReason 为阻断说明。
 */
public record FefoPreviewVO(Long productId,
                            String productName,
                            BigDecimal requestedQty,
                            int nearExpiryDays,
                            BigDecimal totalSellable,
                            boolean blocked,
                            String blockReason,
                            List<Batch> batches) {

    /** 批次预览行：availableQty 现存可售量、plannedQty 本次计划扣减量（不可售批次为 0）。 */
    public record Batch(String batchNo,
                        BigDecimal availableQty,
                        BigDecimal plannedQty,
                        LocalDate expireDate,
                        Long daysToExpire,
                        String expireStatus) {
    }
}
