package com.pharma.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 对账明细行：type = DELIVERY / RETURN / RECEIPT。 */
public record StatementItemVO(LocalDate date,
                              String type,
                              String bizNo,
                              BigDecimal amount,
                              String remark) {
}
