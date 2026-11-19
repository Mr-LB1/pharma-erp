package com.pharma.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 客户应收对账单：期初 + 本期发货 - 退货 - 收款 = 期末。 */
public record StatementVO(Long customerId,
                          String customerName,
                          LocalDate startDate,
                          LocalDate endDate,
                          BigDecimal openingBalance,
                          BigDecimal deliveryAmount,
                          BigDecimal returnAmount,
                          BigDecimal receiptAmount,
                          BigDecimal closingBalance,
                          List<StatementItemVO> items) {
}
