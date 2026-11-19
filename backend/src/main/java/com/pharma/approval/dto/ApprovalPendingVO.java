package com.pharma.approval.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 待审批单据（采购/销售订单统一视图）。
 */
public record ApprovalPendingVO(String bizType,
                                Long bizId,
                                String orderNo,
                                LocalDate orderDate,
                                BigDecimal totalAmount,
                                Long partyId,
                                String partyName,
                                String createdBy,
                                List<ApprovalItemVO> items) {
}
