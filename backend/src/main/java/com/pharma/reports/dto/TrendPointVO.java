package com.pharma.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 工作台近 7 天趋势点：每日入库金额 / 出库金额（缺日补 0）。 */
public record TrendPointVO(LocalDate date, BigDecimal inboundAmount, BigDecimal outboundAmount) {
}
