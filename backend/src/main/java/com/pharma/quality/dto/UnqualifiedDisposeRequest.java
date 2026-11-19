package com.pharma.quality.dto;

import java.time.LocalDate;

/**
 * 不合格品处理（销毁）入参：销毁方式必填，销毁日期/监督人可空（空则取当天/当前用户）。
 */
public record UnqualifiedDisposeRequest(String disposeMethod,
                                        LocalDate disposedAt,
                                        String disposedBy) {
}
