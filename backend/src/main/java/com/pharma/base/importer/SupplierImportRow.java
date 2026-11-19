package com.pharma.base.importer;

import com.alibaba.excel.annotation.ExcelProperty;

/**
 * 供应商导入行模型（§13.3）。
 */
public class SupplierImportRow {

    @ExcelProperty(value = "供应商名称", index = 0)
    private String name;

    @ExcelProperty(value = "统一社会信用代码", index = 1)
    private String creditCode;

    @ExcelProperty(value = "许可证号", index = 2)
    private String licenseNo;

    @ExcelProperty(value = "许可证到期日", index = 3)
    private String licenseExpire;

    @ExcelProperty(value = "结算方式", index = 4)
    private String settleType;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreditCode() {
        return creditCode;
    }

    public void setCreditCode(String creditCode) {
        this.creditCode = creditCode;
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

    public String getSettleType() {
        return settleType;
    }

    public void setSettleType(String settleType) {
        this.settleType = settleType;
    }
}
