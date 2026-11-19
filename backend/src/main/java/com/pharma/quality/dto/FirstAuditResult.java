package com.pharma.quality.dto;

/**
 * 首营审核动作结果。
 */
public record FirstAuditResult(String type, Long id, String firstAuditStatus) {
}
