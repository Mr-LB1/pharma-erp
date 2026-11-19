package com.pharma.common.importer;

/**
 * Excel 读取的一行数据（携带 Excel 行号）。
 */
public class ImportRowData<T> {

    private final int rowNumber;
    private final T data;

    public ImportRowData(int rowNumber, T data) {
        this.rowNumber = rowNumber;
        this.data = data;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public T getData() {
        return data;
    }
}
