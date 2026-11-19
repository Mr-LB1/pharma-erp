package com.pharma.common.export;

import java.util.List;

/**
 * 导出数据集：rows 为实际写出的行（受上限约束），total 为命中总数。
 * 命中数超过上限时 truncated() 为 true，导出接口通过响应头与审计提示调用方。
 *
 * @param rows  导出数据行
 * @param total 命中总行数（未截断前）
 * @param limit 单次导出上限
 */
public record ExportPayload<T>(List<T> rows, long total, int limit) {

    public boolean truncated() {
        return total > rows.size();
    }
}
