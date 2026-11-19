package com.pharma.common;

import java.util.Map;
import java.util.Set;

/**
 * 药品存储条件与仓库温控类型兼容矩阵（P1-14 入库校验 / P2-9 退货回补、调拨目标仓复用）。
 * ROOM_TEMP 只能入常温仓；COOL 可入阴凉/冷藏；COLD 只能入冷藏仓；反向一律拒绝。
 */
public final class TemperatureCompatibility {

    private static final Map<String, Set<String>> ALLOWED_WAREHOUSE_TEMP = Map.of(
            "ROOM_TEMP", Set.of("ROOM_TEMP"),
            "COOL", Set.of("COOL", "COLD"),
            "COLD", Set.of("COLD"));

    private TemperatureCompatibility() {
    }

    /**
     * 校验商品存储条件与目标仓温控类型是否兼容，不兼容抛 400。
     * 任一属性为空时跳过（历史数据/未维护存储条件不做阻断）。
     */
    public static void requireCompatible(String productCode, String storageCond, String tempType,
                                         String warehouseName, String scene) {
        if (storageCond == null || tempType == null) {
            return;
        }
        Set<String> allowed = ALLOWED_WAREHOUSE_TEMP.get(storageCond);
        if (allowed != null && !allowed.contains(tempType)) {
            throw BusinessException.badRequest("温控不兼容：" + scene + "商品 " + productCode
                    + " 存储条件 " + storageCond + " 不可入 " + tempType
                    + " 仓库（" + warehouseName + "）");
        }
    }
}
