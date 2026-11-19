package com.pharma.reports.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;

import java.math.BigDecimal;

/**
 * 进销存汇总导出模型（EasyExcel，P2-7）。
 */
public class StockSummaryExportRow {

    @ExcelProperty("商品编码")
    @ColumnWidth(18)
    private String productCode;

    @ExcelProperty("通用名")
    @ColumnWidth(24)
    private String genericName;

    @ExcelProperty("商品名")
    @ColumnWidth(20)
    private String tradeName;

    @ExcelProperty("规格")
    @ColumnWidth(18)
    private String spec;

    @ExcelProperty("生产厂家")
    @ColumnWidth(24)
    private String manufacturer;

    @ExcelProperty("期初数量")
    @ColumnWidth(12)
    private BigDecimal openingQty;

    @ExcelProperty("入库数量")
    @ColumnWidth(12)
    private BigDecimal inboundQty;

    @ExcelProperty("出库数量")
    @ColumnWidth(12)
    private BigDecimal outboundQty;

    @ExcelProperty("期末数量")
    @ColumnWidth(12)
    private BigDecimal closingQty;

    @ExcelProperty("期初金额")
    @ColumnWidth(14)
    private BigDecimal openingAmount;

    @ExcelProperty("入库金额")
    @ColumnWidth(14)
    private BigDecimal inboundAmount;

    @ExcelProperty("出库金额")
    @ColumnWidth(14)
    private BigDecimal outboundAmount;

    @ExcelProperty("期末金额")
    @ColumnWidth(14)
    private BigDecimal closingAmount;

    public StockSummaryExportRow() {
    }

    public StockSummaryExportRow(String productCode, String genericName, String tradeName, String spec,
                                 String manufacturer, BigDecimal openingQty, BigDecimal inboundQty,
                                 BigDecimal outboundQty, BigDecimal closingQty, BigDecimal openingAmount,
                                 BigDecimal inboundAmount, BigDecimal outboundAmount, BigDecimal closingAmount) {
        this.productCode = productCode;
        this.genericName = genericName;
        this.tradeName = tradeName;
        this.spec = spec;
        this.manufacturer = manufacturer;
        this.openingQty = openingQty;
        this.inboundQty = inboundQty;
        this.outboundQty = outboundQty;
        this.closingQty = closingQty;
        this.openingAmount = openingAmount;
        this.inboundAmount = inboundAmount;
        this.outboundAmount = outboundAmount;
        this.closingAmount = closingAmount;
    }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getGenericName() { return genericName; }
    public void setGenericName(String genericName) { this.genericName = genericName; }
    public String getTradeName() { return tradeName; }
    public void setTradeName(String tradeName) { this.tradeName = tradeName; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getManufacturer() { return manufacturer; }
    public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
    public BigDecimal getOpeningQty() { return openingQty; }
    public void setOpeningQty(BigDecimal openingQty) { this.openingQty = openingQty; }
    public BigDecimal getInboundQty() { return inboundQty; }
    public void setInboundQty(BigDecimal inboundQty) { this.inboundQty = inboundQty; }
    public BigDecimal getOutboundQty() { return outboundQty; }
    public void setOutboundQty(BigDecimal outboundQty) { this.outboundQty = outboundQty; }
    public BigDecimal getClosingQty() { return closingQty; }
    public void setClosingQty(BigDecimal closingQty) { this.closingQty = closingQty; }
    public BigDecimal getOpeningAmount() { return openingAmount; }
    public void setOpeningAmount(BigDecimal openingAmount) { this.openingAmount = openingAmount; }
    public BigDecimal getInboundAmount() { return inboundAmount; }
    public void setInboundAmount(BigDecimal inboundAmount) { this.inboundAmount = inboundAmount; }
    public BigDecimal getOutboundAmount() { return outboundAmount; }
    public void setOutboundAmount(BigDecimal outboundAmount) { this.outboundAmount = outboundAmount; }
    public BigDecimal getClosingAmount() { return closingAmount; }
    public void setClosingAmount(BigDecimal closingAmount) { this.closingAmount = closingAmount; }
}
