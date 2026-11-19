package com.pharma.inventory.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;

/**
 * 仓库调拨导出模型（EasyExcel）：调拨单无往来单位与金额，改列调出/调入仓库。
 */
public class StockTransferExportRow {

    @ExcelProperty("单号")
    @ColumnWidth(22)
    private String billNo;

    @ExcelProperty("调拨日期")
    @ColumnWidth(14)
    private String billDate;

    @ExcelProperty("调出仓库")
    @ColumnWidth(20)
    private String fromWarehouse;

    @ExcelProperty("调入仓库")
    @ColumnWidth(20)
    private String toWarehouse;

    @ExcelProperty("状态")
    @ColumnWidth(14)
    private String status;

    @ExcelProperty("制单人")
    @ColumnWidth(16)
    private String createdBy;

    @ExcelProperty("备注")
    @ColumnWidth(30)
    private String remark;

    public StockTransferExportRow() {
    }

    public StockTransferExportRow(String billNo, String billDate, String fromWarehouse, String toWarehouse,
                                  String status, String createdBy, String remark) {
        this.billNo = billNo;
        this.billDate = billDate;
        this.fromWarehouse = fromWarehouse;
        this.toWarehouse = toWarehouse;
        this.status = status;
        this.createdBy = createdBy;
        this.remark = remark;
    }

    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public String getBillDate() { return billDate; }
    public void setBillDate(String billDate) { this.billDate = billDate; }
    public String getFromWarehouse() { return fromWarehouse; }
    public void setFromWarehouse(String fromWarehouse) { this.fromWarehouse = fromWarehouse; }
    public String getToWarehouse() { return toWarehouse; }
    public void setToWarehouse(String toWarehouse) { this.toWarehouse = toWarehouse; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
