package com.pharma.common;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 枚举合法性统一校验器。
 * 创建/更新时校验枚举字段，非法值抛 400，报错信息含字段名与合法值。
 * 空值（null / 空白串）视为未提供，跳过校验（交由实体默认值或更新语义处理）。
 */
public final class EnumValidator {

    private EnumValidator() {
    }

    /**
     * @param fieldName JSON 字段名（camelCase），用于报错信息
     * @param value     待校验值，null/空白则跳过
     * @param enumClass 合法值集合（枚举 name 即码值）
     */
    public static void validate(String fieldName, String value, Class<? extends Enum<?>> enumClass) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (Enum<?> e : enumClass.getEnumConstants()) {
            if (e.name().equals(value)) {
                return;
            }
        }
        String legal = Arrays.stream(enumClass.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        throw BusinessException.badRequest(fieldName + " 必须为以下值之一: " + legal);
    }
}
