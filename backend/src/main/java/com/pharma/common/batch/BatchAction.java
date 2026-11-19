package com.pharma.common.batch;

/**
 * 批量操作动作（§13.2）。
 * CREATE / UPDATE 使用 items；DELETE / ENABLE / DISABLE 使用 ids。
 */
public enum BatchAction {
    CREATE,
    UPDATE,
    DELETE,
    ENABLE,
    DISABLE;

    /** 动作对应的权限后缀：CREATE→create，DELETE→delete，其余→update。 */
    public String permissionSuffix() {
        return switch (this) {
            case CREATE -> "create";
            case DELETE -> "delete";
            case UPDATE, ENABLE, DISABLE -> "update";
        };
    }

    public boolean usesItems() {
        return this == CREATE || this == UPDATE;
    }
}
