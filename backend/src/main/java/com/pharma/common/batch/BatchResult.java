package com.pharma.common.batch;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量操作汇总结果（§13.2）：{ total, successCount, failedCount, results }。
 */
public class BatchResult {

    private int total;
    private int successCount;
    private int failedCount;
    private List<BatchItemResult> results = new ArrayList<>();

    public BatchResult() {
    }

    public static BatchResult of(List<BatchItemResult> results) {
        BatchResult r = new BatchResult();
        r.results = results == null ? new ArrayList<>() : results;
        r.total = r.results.size();
        r.successCount = (int) r.results.stream().filter(BatchItemResult::isSuccess).count();
        r.failedCount = r.total - r.successCount;
        return r;
    }

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

    public List<BatchItemResult> getResults() {
        return results;
    }

    public void setResults(List<BatchItemResult> results) {
        this.results = results;
    }
}
