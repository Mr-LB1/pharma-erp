package com.pharma.purchase.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;

import java.math.BigDecimal;

/**
 * 采购退货单导出模型（EasyExcel）。
 */
public class PurchaseReturnExportRow {

    @ExcelProperty("单号")
    @ColumnWidth(22)
    private String billNo;

    @ExcelProperty("单据日期")
    @ColumnWidth(14)
    private String billDate;

    @ExcelProperty("供应商")
    @ColumnWidth(28)
    private String partnerName;

    @ExcelProperty("金额")
    @ColumnWidth(16)
    private BigDecimal amount;

    @ExcelProperty("状态")
    @ColumnWidth(18)
    private String status;

    @ExcelProperty("制单人")
    @ColumnWidth(16)
    private String createdBy;

    public PurchaseReturnExportRow() {
    }

    public PurchaseReturnExportRow(String billNo, String billDate, String partnerName,
                                   BigDecimal amount, String status, String createdBy) {
        this.billNo = billNo;
        this.billDate = billDate;
        this.partnerName = partnerName;
        this.amount = amount;
        this.status = status;
        this.createdBy = createdBy;
    }

    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public String getBillDate() { return billDate; }
    public void setBillDate(String billDate) { this.billDate = billDate; }
    public String getPartnerName() { return partnerName; }
    public void setPartnerName(String partnerName) { this.partnerName = partnerName; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
