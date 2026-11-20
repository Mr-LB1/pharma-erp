package com.pharma.reports.dto;

import java.math.BigDecimal;

/**
 * 工作台本月商品销售排行行（按销售金额 revenue 降序，最多 5 条）。
 *
 * @param productId   商品主键
 * @param productName 商品展示名：通用名，缺失时退回商品编码
 * @param quantity    本月销售数量
 * @param amount      本月销售金额（取毛利报表的 revenue，未扣成本）
 */
public record DashboardTopProductVO(Long productId,
                                    String productName,
                                    BigDecimal quantity,
                                    BigDecimal amount) {
}
