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
 * R2 效期阻断：已过期或近效期（expire_date - 今天 <= 30 天）批次不可销售出库，跳过；
 * 无可用批则整单拒绝 400。
 */
class R2ExpiryBlockTest extends AbstractInventoryTest {

    @Test
    void 已过期批次不可售() {
        LocalDate today = LocalDate.now();
        createStock("EXPIRED", today.minusDays(1), new BigDecimal("10"), new BigDecimal("5"));

        assertThat(ledger.isSellable(today.minusDays(1), today)).isFalse();

        assertThatThrownBy(() -> ledger.allocateOutbound(productId, new BigDecimal("5"), warehouseId, "tester", "TEST", 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 近效期30天内批次不可售() {
        LocalDate today = LocalDate.now();
        createStock("NEAR", today.plusDays(10), new BigDecimal("10"), new BigDecimal("5"));

        assertThat(ledger.isSellable(today.plusDays(10), today)).isFalse();
        assertThat(ledger.isSellable(today.plusDays(30), today)).isFalse(); // 恰好 30 天：阻断
        assertThat(ledger.isSellable(today.plusDays(31), today)).isTrue();  // 31 天：可售

        assertThatThrownBy(() -> ledger.allocateOutbound(productId, new BigDecimal("5"), warehouseId, "tester", "TEST", 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 跳过不可售批次仅扣减可售批次() {
        LocalDate today = LocalDate.now();
        createStock("EXPIRED", today.minusDays(1), new BigDecimal("10"), new BigDecimal("5"));
        createStock("NEAR", today.plusDays(10), new BigDecimal("10"), new BigDecimal("5"));
        createStock("GOOD", today.plusDays(100), new BigDecimal("10"), new BigDecimal("5"));

        List<BatchAllocation> result = ledger.allocateOutbound(productId, new BigDecimal("5"), warehouseId, "tester", "TEST", 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).batchNo()).isEqualTo("GOOD");

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "EXPIRED", warehouseId).get().getQuantity())
                .isEqualByComparingTo("10");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "NEAR", warehouseId).get().getQuantity())
                .isEqualByComparingTo("10");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "GOOD", warehouseId).get().getQuantity())
                .isEqualByComparingTo("5");
    }
}
