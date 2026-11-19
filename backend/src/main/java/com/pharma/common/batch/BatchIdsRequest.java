package com.pharma.common.batch;

import java.util.List;

/**
 * 单据批量提交请求体：{ ids: [...] }（§13.1）。
 */
public class BatchIdsRequest {

    private List<Long> ids;

    public List<Long> getIds() {
        return ids;
    }

    public void setIds(List<Long> ids) {
        this.ids = ids;
    }
}
