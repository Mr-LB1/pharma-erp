package com.pharma.reports.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;

import java.math.BigDecimal;

/**
 * 毛利报表导出模型（EasyExcel，P2-7）。
 */
public class GrossProfitExportRow {

    @ExcelProperty("商品编码")
    @ColumnWidth(18)
    private String productCode;

    @ExcelProperty("商品名称")
    @ColumnWidth(24)
    private String productName;

    @ExcelProperty("销售数量")
    @ColumnWidth(12)
    private BigDecimal quantity;

    @ExcelProperty("销售额")
    @ColumnWidth(16)
    private BigDecimal revenue;

    @ExcelProperty("成本")
    @ColumnWidth(16)
    private BigDecimal cost;

    @ExcelProperty("毛利")
    @ColumnWidth(16)
    private BigDecimal profit;

    @ExcelProperty("毛利率(%)")
    @ColumnWidth(12)
    private BigDecimal margin;

    public GrossProfitExportRow() {
    }

    public GrossProfitExportRow(String productCode, String productName, BigDecimal quantity,
                                BigDecimal revenue, BigDecimal cost, BigDecimal profit, BigDecimal margin) {
        this.productCode = productCode;
        this.productName = productName;
        this.quantity = quantity;
        this.revenue = revenue;
        this.cost = cost;
        this.profit = profit;
        this.margin = margin;
    }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getRevenue() { return revenue; }
    public void setRevenue(BigDecimal revenue) { this.revenue = revenue; }
    public BigDecimal getCost() { return cost; }
    public void setCost(BigDecimal cost) { this.cost = cost; }
    public BigDecimal getProfit() { return profit; }
    public void setProfit(BigDecimal profit) { this.profit = profit; }
    public BigDecimal getMargin() { return margin; }
    public void setMargin(BigDecimal margin) { this.margin = margin; }
}
