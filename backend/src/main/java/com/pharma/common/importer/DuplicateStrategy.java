package com.pharma.common.importer;

/**
 * 导入重复数据处理策略（§13.3）。
 */
public enum DuplicateStrategy {
    /** 已存在则跳过，计入 skipped。 */
    SKIP,
    /** 已存在则按业务键更新。 */
    UPDATE,
    /** 已存在即该行失败。 */
    ERROR;

    public static DuplicateStrategy parse(String value) {
        if (value == null || value.isBlank()) {
            return SKIP;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw com.pharma.common.BusinessException.badRequest(
                    "duplicateStrategy 必须为 SKIP/UPDATE/ERROR 之一");
        }
    }
}
