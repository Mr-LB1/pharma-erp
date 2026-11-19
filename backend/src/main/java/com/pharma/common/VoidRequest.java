package com.pharma.common;

/**
 * 作废请求：可选填写作废原因，写入审计日志 detail。
 */
public record VoidRequest(String reason) {
}
