package com.pharma.common.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入执行结果（§13.3）：
 * { total, successCount, failedCount, insertedCount, updatedCount, skippedCount, errors }。
 * successCount = insertedCount + updatedCount（跳过的行不计入成功/失败）。
 */
public class ImportResult {

    private int total;
    private int successCount;
    private int failedCount;
    private int insertedCount;
    private int updatedCount;
    private int skippedCount;
    private List<ImportError> errors = new ArrayList<>();

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public int getInsertedCount() {
        return insertedCount;
    }

    public void setInsertedCount(int insertedCount) {
        this.insertedCount = insertedCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public void setUpdatedCount(int updatedCount) {
        this.updatedCount = updatedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(int skippedCount) {
        this.skippedCount = skippedCount;
    }

    public List<ImportError> getErrors() {
        return errors;
    }

    public void setErrors(List<ImportError> errors) {
        this.errors = errors;
    }
}
