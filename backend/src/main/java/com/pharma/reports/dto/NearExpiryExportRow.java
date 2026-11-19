package com.pharma.reports.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;

import java.math.BigDecimal;

/**
 * 近效期导出模型（EasyExcel）。
 */
public class NearExpiryExportRow {

    @ExcelProperty("商品编码")
    @ColumnWidth(18)
    private String productCode;

    @ExcelProperty("商品名称")
    @ColumnWidth(24)
    private String productName;

    @ExcelProperty("规格")
    @ColumnWidth(18)
    private String spec;

    @ExcelProperty("生产厂家")
    @ColumnWidth(24)
    private String manufacturer;

    @ExcelProperty("批号")
    @ColumnWidth(16)
    private String batchNo;

    @ExcelProperty("生产日期")
    @ColumnWidth(14)
    private String produceDate;

    @ExcelProperty("有效期至")
    @ColumnWidth(14)
    private String expireDate;

    @ExcelProperty("剩余天数")
    @ColumnWidth(12)
    private Long daysLeft;

    @ExcelProperty("库存数量")
    @ColumnWidth(12)
    private BigDecimal quantity;

    public NearExpiryExportRow() {
    }

    public NearExpiryExportRow(String productCode, String productName, String spec, String manufacturer,
                               String batchNo, String produceDate, String expireDate, Long daysLeft,
                               BigDecimal quantity) {
        this.productCode = productCode;
        this.productName = productName;
        this.spec = spec;
        this.manufacturer = manufacturer;
        this.batchNo = batchNo;
        this.produceDate = produceDate;
        this.expireDate = expireDate;
        this.daysLeft = daysLeft;
        this.quantity = quantity;
    }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getProduceDate() { return produceDate; }
    public void setProduceDate(String produceDate) { this.produceDate = produceDate; }
    public String getExpireDate() { return expireDate; }
    public void setExpireDate(String expireDate) { this.expireDate = expireDate; }
    public Long getDaysLeft() { return daysLeft; }
    public void setDaysLeft(Long daysLeft) { this.daysLeft = daysLeft; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
}
