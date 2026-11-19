package com.pharma.common.batch;

import java.util.List;

/**
 * 批量作废请求体：{ ids: [...], reason: "作废原因" }。
 * reason 可选，逐条写入各自作废审计；批量汇总审计另记一条 BATCH_VOID。
 */
public class BatchVoidRequest {

    private List<Long> ids;
    private String reason;

    public List<Long> getIds() {
        return ids;
    }

    public void setIds(List<Long> ids) {
        this.ids = ids;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
