package com.pharma.base.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.context.ApplicationEvent;

import java.util.Collections;
import java.util.Map;

/**
 * 门店主数据变更事件（POS 同步预留）。
 *
 * <p>ERP 是门店主数据源。集成框架尚未实现，本类只做解耦的事件载体，
 * 不实现任何监听器。集成落地时由监听器将事件写入 outbox，再投递到 POS：
 *
 * <p>事件消费方（集成框架）需把 {@link #getSnapshot()} 中的门店全字段
 * （code / name / type / warehouseCode / warehouseName / licenseNo /
 *  licenseExpire / config / status 等）映射为 POS 的 {@code STORE_MASTER_UPSERT} 报文：
 * <pre>
 * {
 *   "msgType": "STORE_MASTER_UPSERT",
 *   "action":  "CREATE | UPDATE | DELETE",
 *   "store": {
 *     "storeCode": "...", "storeName": "...", "storeType": "DIRECT|FRANCHISE",
 *     "warehouseCode": "...", "warehouseName": "...",
 *     "licenseNo": "...", "licenseExpire": "yyyy-MM-dd",
 *     "config": { ... }, "status": "ACTIVE|INACTIVE"
 *   }
 * }
 * </pre>
 * DELETE 事件的 snapshot 为删除前的快照。
 */
public class StoreChangedEvent extends ApplicationEvent {

    /** 变更动作。 */
    public enum Action {
        CREATE,
        UPDATE,
        DELETE
    }

    private final Long storeId;
    private final String code;
    private final Action action;
    private final Map<String, Object> snapshot;

    public StoreChangedEvent(Object source, Long storeId, String code, Action action, Map<String, Object> snapshot) {
        super(source);
        this.storeId = storeId;
        this.code = code;
        this.action = action;
        this.snapshot = snapshot == null ? Collections.emptyMap() : Collections.unmodifiableMap(snapshot);
    }

    public Long getStoreId() {
        return storeId;
    }

    public String getCode() {
        return code;
    }

    public Action getAction() {
        return action;
    }

    /** 门店全字段快照（供后续 outbox/POS 报文映射）。 */
    public Map<String, Object> getSnapshot() {
        return snapshot;
    }

    /** 序列化到 outbox 时忽略事件源对象，只保留业务字段。 */
    @JsonIgnore
    @Override
    public Object getSource() {
        return super.getSource();
    }
}
