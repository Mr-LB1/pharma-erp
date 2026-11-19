package com.pharma.common.importer;

/**
 * 导入错误项：{ row, field, message }，row 为 Excel 数据行号（表头第 1 行，数据从第 2 行起）。
 */
public class ImportError {

    private int row;
    private String field;
    private String message;

    public ImportError() {
    }

    public ImportError(int row, String field, String message) {
        this.row = row;
        this.field = field;
        this.message = message;
    }

    public int getRow() {
        return row;
    }

    public void setRow(int row) {
        this.row = row;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
