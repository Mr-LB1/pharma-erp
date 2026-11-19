package com.pharma.base.importer;

import com.alibaba.excel.annotation.ExcelProperty;

/**
 * 商品导入行模型（§13.3）。全部使用字符串接收，便于逐单元格返回校验错误。
 */
public class ProductImportRow {

    @ExcelProperty(value = "商品编码", index = 0)
    private String code;

    @ExcelProperty(value = "通用名", index = 1)
    private String genericName;

    @ExcelProperty(value = "商品名", index = 2)
    private String tradeName;

    @ExcelProperty(value = "规格", index = 3)
    private String spec;

    @ExcelProperty(value = "生产厂家", index = 4)
    private String manufacturer;

    @ExcelProperty(value = "处方分类", index = 5)
    private String rxCategory;

    @ExcelProperty(value = "存储条件", index = 6)
    private String storageCond;

    @ExcelProperty(value = "首营状态", index = 7)
    private String firstAuditStatus;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getGenericName() {
        return genericName;
    }

    public void setGenericName(String genericName) {
        this.genericName = genericName;
    }

    public String getTradeName() {
        return tradeName;
    }

    public void setTradeName(String tradeName) {
        this.tradeName = tradeName;
    }

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getRxCategory() {
        return rxCategory;
    }

    public void setRxCategory(String rxCategory) {
        this.rxCategory = rxCategory;
    }

    public String getStorageCond() {
        return storageCond;
    }

    public void setStorageCond(String storageCond) {
        this.storageCond = storageCond;
    }

    public String getFirstAuditStatus() {
        return firstAuditStatus;
    }

    public void setFirstAuditStatus(String firstAuditStatus) {
        this.firstAuditStatus = firstAuditStatus;
    }
}
