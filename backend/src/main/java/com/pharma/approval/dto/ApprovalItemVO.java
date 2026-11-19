package com.pharma.approval.dto;

import java.math.BigDecimal;

/**
 * 待审批单据明细（采购/销售订单行统一视图），供审批人查看后再决策。
 */
public record ApprovalItemVO(Long productId,
                             String productName,
                             BigDecimal quantity,
                             BigDecimal unitPrice,
                             BigDecimal amount) {
}
