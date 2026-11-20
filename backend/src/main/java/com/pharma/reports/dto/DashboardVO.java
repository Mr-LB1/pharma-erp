package com.pharma.reports.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工作台 Dashboard：卡片数字 + 前端跳转路径 + 近 7 天趋势 + 待办/近效期/销售排行聚合。
 * 前三组明细（todos / nearExpiry / topProducts）由 ReportService 一次聚合返回，避免前端多次降级请求；
 * 待办仅在当前用户具备 approval:view 权限时查询，其余情况为空列表。
 */
public record DashboardVO(long pendingApprovalCount,
                          String pendingApprovalPath,
                          long nearExpiryCount,
                          String nearExpiryPath,
                          long licenseExpiringCount,
                          String licenseExpiringPath,
                          BigDecimal todayPurchaseAmount,
                          String todayPurchasePath,
                          BigDecimal todaySalesAmount,
                          String todaySalesPath,
                          List<TrendPointVO> trends,
                          List<DashboardTodoVO> todos,
                          List<DashboardNearExpiryVO> nearExpiry,
                          List<DashboardTopProductVO> topProducts) {
}
