package com.pharma.common.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入预览行：包含行号、原始数据、是否通过校验及该行错误。
 */
public class ImportPreviewRow {

    private int row;
    private boolean valid;
    private Object data;
    private List<ImportError> errors = new ArrayList<>();

    public ImportPreviewRow() {
    }

    public ImportPreviewRow(int row, boolean valid, Object data, List<ImportError> errors) {
        this.row = row;
        this.valid = valid;
        this.data = data;
        this.errors = errors == null ? new ArrayList<>() : errors;
    }

    public int getRow() {
        return row;
    }

    public void setRow(int row) {
        this.row = row;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public List<ImportError> getErrors() {
        return errors;
    }

    public void setErrors(List<ImportError> errors) {
        this.errors = errors;
    }
}
