package com.pharma.common.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入预览结果（§13.3）：{ total, validCount, invalidCount, preview, errors }。
 * preview 仅返回前 20 行。
 */
public class ImportPreviewResult {

    private int total;
    private int validCount;
    private int invalidCount;
    private List<ImportPreviewRow> preview = new ArrayList<>();
    private List<ImportError> errors = new ArrayList<>();

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getValidCount() {
        return validCount;
    }

    public void setValidCount(int validCount) {
        this.validCount = validCount;
    }

    public int getInvalidCount() {
        return invalidCount;
    }

    public void setInvalidCount(int invalidCount) {
        this.invalidCount = invalidCount;
    }

    public List<ImportPreviewRow> getPreview() {
        return preview;
    }

    public void setPreview(List<ImportPreviewRow> preview) {
        this.preview = preview;
    }

    public List<ImportError> getErrors() {
        return errors;
    }

    public void setErrors(List<ImportError> errors) {
        this.errors = errors;
    }
}
