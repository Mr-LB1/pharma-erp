package com.pharma.common;

/**
 * 主数据列表状态过滤（P0-4 软删除配套）：默认只看 ACTIVE，可显式查 INACTIVE 以恢复启用。
 */
public final class StatusFilter {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
    public static final String ALL = "ALL";

    private StatusFilter() {
    }

    /** null/空白 → ACTIVE；仅接受 ACTIVE / INACTIVE / ALL（大小写不敏感） */
    public static String normalize(String status) {
        if (status == null || status.isBlank()) {
            return ACTIVE;
        }
        String value = status.trim().toUpperCase();
        if (!ACTIVE.equals(value) && !INACTIVE.equals(value) && !ALL.equals(value)) {
            throw BusinessException.badRequest("status 必须为 ACTIVE / INACTIVE / ALL");
        }
        return value;
    }
}
