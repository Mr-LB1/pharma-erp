package com.pharma.common.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 导入单元格解析工具：统一处理去空白、日期（含 Excel 日期序列号）、金额、整数与枚举。
 */
public final class ImportParseUtils {

    private static final DateTimeFormatter[] DATE_FORMATTERS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d")
    };

    private ImportParseUtils() {
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 解析日期：支持 yyyy-MM-dd、yyyy/M/d、yyyy.M.d、带时间字符串，以及 Excel 日期序列号。
     *
     * @throws IllegalArgumentException 格式不正确
     */
    public static LocalDate parseDate(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        if (value.matches("\\d{4,5}(\\.\\d+)?")) {
            double serial = Double.parseDouble(value);
            if (serial > 0 && serial < 80000) {
                return LocalDate.of(1899, 12, 30).plusDays((long) serial);
            }
        }
        String datePart = value.length() > 10 ? value.substring(0, 10) : value;
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(datePart, formatter);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd: " + value);
    }

    public static BigDecimal parseDecimal(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(value.replace(",", ""));
            if (decimal.signum() < 0) {
                throw new IllegalArgumentException("金额不能为负数: " + value);
            }
            return decimal;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("金额格式不正确: " + value);
        }
    }

    public static Long parseLong(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("整数格式不正确: " + value);
        }
    }

    /**
     * 枚举校验并返回规范化后的值；空值返回 null。
     */
    public static String checkEnum(String field, String raw, Class<? extends Enum<?>> enumClass, int row,
                                   List<ImportError> errors) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        for (Enum<?> e : enumClass.getEnumConstants()) {
            if (e.name().equals(value)) {
                return value;
            }
        }
        errors.add(new ImportError(row, field, "必须为以下值之一: " + legalValues(enumClass)));
        return null;
    }

    public static String legalValues(Class<? extends Enum<?>> enumClass) {
        StringBuilder sb = new StringBuilder();
        for (Enum<?> e : enumClass.getEnumConstants()) {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(e.name());
        }
        return sb.toString();
    }

    public static void required(String field, String raw, String label, int row, List<ImportError> errors) {
        if (trimToNull(raw) == null) {
            errors.add(new ImportError(row, field, label + "不能为空"));
        }
    }
}
