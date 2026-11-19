package com.pharma.quality.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 首营待审核项（product/supplier/customer 统一视图）。
 */
public record FirstAuditPendingVO(String type,
                                  Long id,
                                  String name,
                                  String code,
                                  String firstAuditStatus,
                                  LocalDate licenseExpire,
                                  LocalDateTime createdAt) {
}
