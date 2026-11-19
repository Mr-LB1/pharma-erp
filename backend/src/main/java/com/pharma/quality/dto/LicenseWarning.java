package com.pharma.quality.dto;

import java.time.LocalDate;

/**
 * 证照预警项（供应商/客户）。
 */
public record LicenseWarning(String type,
                             Long id,
                             String name,
                             String licenseNo,
                             LocalDate licenseExpire,
                             long daysLeft) {
}
