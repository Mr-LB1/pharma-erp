package com.pharma.reports.dto;

import java.time.LocalDate;

/**
 * 工作台待办事项（当前用户可见的待审批单据摘要）。
 * 仅当用户具备 approval:view 权限时由 ReportService 聚合，否则后端返回空列表。
 *
 * @param bizType   单据类型：PURCHASE_ORDER / SALES_ORDER / SALES_RETURN / PURCHASE_RECEIVE /
 *                  SALES_DELIVERY / PURCHASE_RETURN / STOCK_TRANSFER
 * @param bizId     单据主键，与 bizType 组合定位待办单据
 * @param title     待办标题：单据类型中文 + 单号 + 往来单位，如「采购订单 PO-001 · 某供应商」
 * @param createdAt 待办产生时间，取单据业务日期（order_date），前端展示为相对时间
 */
public record DashboardTodoVO(String bizType,
                              Long bizId,
                              String title,
                              LocalDate createdAt) {
}
