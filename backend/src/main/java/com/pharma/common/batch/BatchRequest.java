package com.pharma.common.batch;

import java.util.List;

/**
 * 批量操作请求（§13.2）：{ action, ids?, items? }。
 *
 * @param <T> 资源 payload 类型，CREATE/UPDATE 时由 items 承载
 */
public class BatchRequest<T> {

    private BatchAction action;
    private List<Long> ids;
    private List<T> items;

    public BatchAction getAction() {
        return action;
    }

    public void setAction(BatchAction action) {
        this.action = action;
    }

    public List<Long> getIds() {
        return ids;
    }

    public void setIds(List<Long> ids) {
        this.ids = ids;
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }
}
