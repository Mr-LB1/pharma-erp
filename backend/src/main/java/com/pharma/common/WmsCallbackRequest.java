package com.pharma.common;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * WMS 回传请求（批次十四）：审批通过下发 WMS 后，由 WMS 回传实际数量，按实量执行库存/财务。
 *
 * @param wmsNo        WMS 单号（可空）
 * @param callbackTime 回传时间（可空，默认当前时间）
 * @param items        逐行实际数量；为空表示全部按单据量回传（便于手工/冒烟场景）
 */
public record WmsCallbackRequest(String wmsNo,
                                 LocalDateTime callbackTime,
                                 List<Item> items) {

    /** 明细实量：itemId 为单据明细主键，actualQty 为 WMS 实际数量。 */
    public record Item(Long itemId, BigDecimal actualQty) {
    }
}
