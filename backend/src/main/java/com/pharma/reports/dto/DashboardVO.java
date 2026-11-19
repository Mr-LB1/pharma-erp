package com.pharma.reports.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工作台 Dashboard：卡片数字 + 前端跳转路径 + 近 7 天趋势。
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
                          List<TrendPointVO> trends) {
}
