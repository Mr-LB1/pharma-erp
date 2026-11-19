package com.pharma.base.dto;

import java.time.LocalDate;

/**
 * 门店证照到期预警项。
 */
public record StoreLicenseWarning(Long id,
                                  String code,
                                  String name,
                                  String licenseNo,
                                  LocalDate licenseExpire,
                                  long daysLeft) {
}
