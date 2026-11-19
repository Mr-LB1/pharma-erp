package com.pharma.common.importer;

import com.pharma.common.BusinessException;

import java.util.Map;

/**
 * 资源名 → 权限前缀映射（§13.2）。
 * 门店模块历史权限码为 base:store:*（V10），故 stores 映射为 base:store，其余为单数资源名。
 */
public final class ResourcePrefixes {

    private static final Map<String, String> PREFIXES = Map.of(
            "products", "product",
            "suppliers", "supplier",
            "customers", "customer",
            "warehouses", "warehouse",
            "stores", "base:store"
    );

    private ResourcePrefixes() {
    }

    public static String of(String resource) {
        String prefix = PREFIXES.get(resource);
        if (prefix == null) {
            throw BusinessException.notFound("不支持的导入资源: " + resource);
        }
        return prefix;
    }
}
