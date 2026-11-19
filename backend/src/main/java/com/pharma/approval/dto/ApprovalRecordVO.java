package com.pharma.approval.dto;

import java.time.LocalDateTime;

/**
 * 审批记录 VO。
 */
public record ApprovalRecordVO(Long id,
                               String bizType,
                               Long bizId,
                               String node,
                               String approver,
                               String status,
                               String comment,
                               LocalDateTime createdAt) {
}
