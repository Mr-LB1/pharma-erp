package com.pharma.purchase.service;

import com.pharma.purchase.entity.PurchaseReceiveItem;
import com.pharma.purchase.entity.PurchaseReturnItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行实量口径单元测试（不依赖数据库）：actualQty 未回传时回退单据量，回传后以实量为准。
 * 覆盖采购入库 / 采购退货的 {@code executedQty} 辅助方法，防止作废冲回、订单进度、导出金额口径回退。
 */
class ExecutedQtyTest {

    /** 未回传实量（actualQty = null）时按单据量 quantity 计。 */
    @Test
    void 未回传实量时回退单据量() {
        PurchaseReceiveItem receiveItem = new PurchaseReceiveItem();
        receiveItem.setQuantity(new BigDecimal("10.00"));
        assertThat(PurchaseReceiveService.executedQty(receiveItem)).isEqualByComparingTo("10");

        PurchaseReturnItem returnItem = new PurchaseReturnItem();
        returnItem.setQuantity(new BigDecimal("4.00"));
        assertThat(PurchaseReturnService.executedQty(returnItem)).isEqualByComparingTo("4");
    }

    /** 已回传实量时以 actualQty 为准：部分实量不得再按单据量统计。 */
    @Test
    void 已回传实量时优先实量() {
        PurchaseReceiveItem receiveItem = new PurchaseReceiveItem();
        receiveItem.setQuantity(new BigDecimal("10.00"));
        receiveItem.setActualQty(new BigDecimal("6.00"));
        assertThat(PurchaseReceiveService.executedQty(receiveItem)).isEqualByComparingTo("6");

        PurchaseReturnItem returnItem = new PurchaseReturnItem();
        returnItem.setQuantity(new BigDecimal("4.00"));
        returnItem.setActualQty(new BigDecimal("2.00"));
        assertThat(PurchaseReturnService.executedQty(returnItem)).isEqualByComparingTo("2");
    }

    /** 实量为 0（WMS 确认未到货 / 未退货）时返回 0，作废逻辑据此跳过该行。 */
    @Test
    void 实量为零时返回零() {
        PurchaseReceiveItem receiveItem = new PurchaseReceiveItem();
        receiveItem.setQuantity(new BigDecimal("10.00"));
        receiveItem.setActualQty(BigDecimal.ZERO);
        assertThat(PurchaseReceiveService.executedQty(receiveItem)).isEqualByComparingTo("0");
    }
}
