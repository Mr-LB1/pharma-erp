package com.pharma.reports.dto;

import java.math.BigDecimal;

/** 毛利报表行：销售额 - 成本 = 毛利。 */
public record GrossProfitVO(Long productId,
                            String productCode,
                            String productName,
                            BigDecimal quantity,
                            BigDecimal revenue,
                            BigDecimal cost,
                            BigDecimal grossProfit,
                            BigDecimal grossMargin) {
}
