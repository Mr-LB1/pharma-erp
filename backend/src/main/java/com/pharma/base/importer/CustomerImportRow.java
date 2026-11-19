package com.pharma.base.importer;

import com.alibaba.excel.annotation.ExcelProperty;

/**
 * 客户导入行模型（§13.3）。
 */
public class CustomerImportRow {

    @ExcelProperty(value = "客户名称", index = 0)
    private String name;

    @ExcelProperty(value = "许可证号", index = 1)
    private String licenseNo;

    @ExcelProperty(value = "许可证到期日", index = 2)
    private String licenseExpire;

    @ExcelProperty(value = "资信等级", index = 3)
    private String creditLevel;

    @ExcelProperty(value = "授信额度", index = 4)
    private String creditLimit;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLicenseNo() {
        return licenseNo;
    }

    public void setLicenseNo(String licenseNo) {
        this.licenseNo = licenseNo;
    }

    public String getLicenseExpire() {
        return licenseExpire;
    }

    public void setLicenseExpire(String licenseExpire) {
        this.licenseExpire = licenseExpire;
    }

    public String getCreditLevel() {
        return creditLevel;
    }

    public void setCreditLevel(String creditLevel) {
        this.creditLevel = creditLevel;
    }

    public String getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(String creditLimit) {
        this.creditLimit = creditLimit;
    }
}
