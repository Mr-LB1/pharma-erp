package com.pharma.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 工作台近效期预警行（库存批次维度，最多 8 条，按效期升序）。
 *
 * @param productId   商品主键
 * @param productName 商品展示名：通用名（商品名）+ 规格拼接，无商品名时退回通用名
 * @param batchNo     批号
 * @param expireDate  批次效期（含已过期批次）
 * @param quantity    当前库存数量
 * @param daysLeft    距效期的剩余天数，已过期为负数
 */
public record DashboardNearExpiryVO(Long productId,
                                    String productName,
                                    String batchNo,
                                    LocalDate expireDate,
                                    BigDecimal quantity,
                                    long daysLeft) {
}
