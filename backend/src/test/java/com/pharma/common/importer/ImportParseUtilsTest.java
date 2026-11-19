package com.pharma.common.importer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Excel 单元格解析与校验工具单元测试。
 */
class ImportParseUtilsTest {

    @Test
    void 日期支持多种格式与Excel序列号() {
        assertThat(ImportParseUtils.parseDate("2026-11-19")).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(ImportParseUtils.parseDate("2026/1/5")).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(ImportParseUtils.parseDate("2026-11-19 00:00:00")).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(ImportParseUtils.parseDate("46345")).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(ImportParseUtils.parseDate("  ")).isNull();
    }

    @Test
    void 非法日期抛可读异常() {
        assertThatThrownBy(() -> ImportParseUtils.parseDate("2026-13-40"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("日期格式");
    }

    @Test
    void 金额解析支持千分位且拒绝负数() {
        assertThat(ImportParseUtils.parseDecimal("1,234.50")).isEqualByComparingTo(new BigDecimal("1234.50"));
        assertThatThrownBy(() -> ImportParseUtils.parseDecimal("-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImportParseUtils.parseDecimal("abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 枚举校验写入字段级错误() {
        List<ImportError> errors = new ArrayList<>();
        assertThat(ImportParseUtils.checkEnum("rxCategory", "RX", com.pharma.common.enums.RxCategory.class, 2, errors))
                .isEqualTo("RX");
        assertThat(errors).isEmpty();

        assertThat(ImportParseUtils.checkEnum("rxCategory", "BAD", com.pharma.common.enums.RxCategory.class, 3, errors))
                .isNull();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getRow()).isEqualTo(3);
        assertThat(errors.get(0).getField()).isEqualTo("rxCategory");
        assertThat(errors.get(0).getMessage()).contains("RX/OTC/SPECIAL");
    }
}
