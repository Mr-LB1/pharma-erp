package com.pharma.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 单据业务日期校验（P2-6）：
 * - 不允许未来日期（「业务日期不能晚于今天」）；
 * - 不允许早于 today - maxBackDays（默认 365 天，可用 app.biz-date.max-back-days 调整）。
 * 请求未传日期时各服务默认取 today，故 null 直接放行。
 */
@Component
public class DateValidator {

    private final int maxBackDays;

    public DateValidator(@Value("${app.biz-date.max-back-days:365}") int maxBackDays) {
        this.maxBackDays = maxBackDays <= 0 ? 365 : maxBackDays;
    }

    public void validateBizDate(LocalDate date, String fieldLabel) {
        if (date == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (date.isAfter(today)) {
            throw BusinessException.badRequest(fieldLabel + "不能晚于今天");
        }
        LocalDate earliest = today.minusDays(maxBackDays);
        if (date.isBefore(earliest)) {
            throw BusinessException.badRequest(fieldLabel + "超出允许范围（不得早于 " + earliest + "）");
        }
    }
}
