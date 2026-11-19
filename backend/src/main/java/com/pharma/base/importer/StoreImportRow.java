package com.pharma.base.importer;

import com.alibaba.excel.annotation.ExcelProperty;

/**
 * 门店导入行模型（§13.3）。
 */
public class StoreImportRow {

    @ExcelProperty(value = "门店编码", index = 0)
    private String code;

    @ExcelProperty(value = "门店名称", index = 1)
    private String name;

    @ExcelProperty(value = "门店类型", index = 2)
    private String type;

    @ExcelProperty(value = "关联仓库ID", index = 3)
    private String warehouseId;

    @ExcelProperty(value = "地址", index = 4)
    private String address;

    @ExcelProperty(value = "联系人", index = 5)
    private String contactName;

    @ExcelProperty(value = "联系电话", index = 6)
    private String contactPhone;

    @ExcelProperty(value = "许可证号", index = 7)
    private String licenseNo;

    @ExcelProperty(value = "许可证到期日", index = 8)
    private String licenseExpire;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(String warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
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
}
