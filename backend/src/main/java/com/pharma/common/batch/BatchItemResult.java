package com.pharma.common.batch;

/**
 * 批量操作单条结果：{ index, success, id?, message? }。
 * index 从 0 开始，对应请求数组下标。
 */
public class BatchItemResult {

    private int index;
    private boolean success;
    private Long id;
    private String message;

    public BatchItemResult() {
    }

    public BatchItemResult(int index, boolean success, Long id, String message) {
        this.index = index;
        this.success = success;
        this.id = id;
        this.message = message;
    }

    public static BatchItemResult ok(int index, Long id) {
        return new BatchItemResult(index, true, id, null);
    }

    public static BatchItemResult fail(int index, String message) {
        return new BatchItemResult(index, false, null, message);
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
