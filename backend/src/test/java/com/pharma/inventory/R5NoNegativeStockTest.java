package com.pharma.inventory;

import com.pharma.common.BusinessException;
import com.pharma.inventory.service.InventoryLedgerService.BatchAllocation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R5 库存不可为负：出库数量总和 > 该商品可售批次库存总和 → 拒绝 400，且库存不被扣减。
 */
class R5NoNegativeStockTest extends AbstractInventoryTest {

    @Test
    void 出库量超过可售库存被拒绝() {
        LocalDate today = LocalDate.now();
        createStock("A", today.plusDays(100), new BigDecimal("5"), new BigDecimal("5"));

        assertThatThrownBy(() -> ledger.allocateOutbound(productId, new BigDecimal("10"), warehouseId, "tester", "TEST", 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));

        // 库存未被扣减
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "A", warehouseId).get().getQuantity())
                .isEqualByComparingTo("5");
    }

    @Test
    void 出库量恰好等于可售库存可成功且不为负() {
        LocalDate today = LocalDate.now();
        createStock("A", today.plusDays(100), new BigDecimal("5"), new BigDecimal("5"));

        List<BatchAllocation> result = ledger.allocateOutbound(productId, new BigDecimal("5"), warehouseId, "tester", "TEST", 1L);

        assertThat(result).hasSize(1);
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "A", warehouseId).get().getQuantity())
                .isEqualByComparingTo("0");
    }

    @Test
    void 部分扣减后再超量出库被拒绝() {
        LocalDate today = LocalDate.now();
        createStock("A", today.plusDays(100), new BigDecimal("5"), new BigDecimal("5"));

        ledger.allocateOutbound(productId, new BigDecimal("3"), warehouseId, "tester", "TEST", 1L);

        assertThatThrownBy(() -> ledger.allocateOutbound(productId, new BigDecimal("3"), warehouseId, "tester", "TEST", 2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "A", warehouseId).get().getQuantity())
                .isEqualByComparingTo("2");
    }
}
