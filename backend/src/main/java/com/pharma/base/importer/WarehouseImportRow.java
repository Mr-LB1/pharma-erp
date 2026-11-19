package com.pharma.base.importer;

import com.alibaba.excel.annotation.ExcelProperty;

/**
 * 仓库导入行模型（§13.3）。
 */
public class WarehouseImportRow {

    @ExcelProperty(value = "仓库名称", index = 0)
    private String name;

    @ExcelProperty(value = "温控类型", index = 1)
    private String tempType;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTempType() {
        return tempType;
    }

    public void setTempType(String tempType) {
        this.tempType = tempType;
    }
}
