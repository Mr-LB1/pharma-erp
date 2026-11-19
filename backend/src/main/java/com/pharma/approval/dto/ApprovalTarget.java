package com.pharma.approval.dto;

import java.math.BigDecimal;

/**
 * 单据审批上下文（采购入库/销售出库/采购退货/仓库调拨统一视图）。
 * 供 ApprovalService 做状态守卫、回避校验与金额分级审批。
 *
 * @param docNo     单号（入库单号/出库单号/退货单号/调拨单号）
 * @param status    当前状态
 * @param createdBy 制单人
 * @param amount    单据金额（用于 approval_config 分级）
 */
public record ApprovalTarget(String docNo,
                             String status,
                             String createdBy,
                             BigDecimal amount) {
}
