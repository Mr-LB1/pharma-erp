package com.pharma.inventory.service;

import com.pharma.inventory.entity.StockTransferItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调拨执行实量口径单元测试（不依赖数据库）：actualQty 未回传时回退单据量，回传后以实量为准。
 * 覆盖 {@code StockTransferService.executedQty}，防止部分实调后作废按单据量冲回导致库存虚增。
 */
class StockTransferExecutedQtyTest {

    /** 未回传实量（actualQty = null）时按单据量 quantity 计。 */
    @Test
    void 未回传实量时回退单据量() {
        StockTransferItem item = new StockTransferItem();
        item.setQuantity(new BigDecimal("10.00"));
        assertThat(StockTransferService.executedQty(item)).isEqualByComparingTo("10");
    }

    /** 已回传实量时以 actualQty 为准。 */
    @Test
    void 已回传实量时优先实量() {
        StockTransferItem item = new StockTransferItem();
        item.setQuantity(new BigDecimal("10.00"));
        item.setActualQty(new BigDecimal("6.00"));
        assertThat(StockTransferService.executedQty(item)).isEqualByComparingTo("6");
    }

    /** 实量为 0（WMS 确认未实际调拨）时返回 0，作废逻辑据此跳过该行。 */
    @Test
    void 实量为零时返回零() {
        StockTransferItem item = new StockTransferItem();
        item.setQuantity(new BigDecimal("10.00"));
        item.setActualQty(BigDecimal.ZERO);
        assertThat(StockTransferService.executedQty(item)).isEqualByComparingTo("0");
    }
}
