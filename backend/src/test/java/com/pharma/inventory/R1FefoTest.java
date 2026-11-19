package com.pharma.inventory;

import com.pharma.inventory.service.InventoryLedgerService.BatchAllocation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1 FEFO：出库按 expire_date 升序（最早到期先出），同效期按 batch_no 升序，跨批次依次扣减。
 */
class R1FefoTest extends AbstractInventoryTest {

    @Test
    void 最早到期批次优先出库() {
        LocalDate today = LocalDate.now();
        createStock("LATE", today.plusDays(200), new BigDecimal("10"), new BigDecimal("5"));
        createStock("EARLY", today.plusDays(100), new BigDecimal("10"), new BigDecimal("5"));

        List<BatchAllocation> result = ledger.allocateOutbound(productId, new BigDecimal("5"), warehouseId, "tester", "TEST", 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).batchNo()).isEqualTo("EARLY");

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "EARLY", warehouseId).get().getQuantity())
                .isEqualByComparingTo("5");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "LATE", warehouseId).get().getQuantity())
                .isEqualByComparingTo("10");
    }

    @Test
    void 同效期按批号升序出库() {
        LocalDate today = LocalDate.now();
        LocalDate sameExpire = today.plusDays(120);
        createStock("BATCH-002", sameExpire, new BigDecimal("10"), new BigDecimal("5"));
        createStock("BATCH-001", sameExpire, new BigDecimal("10"), new BigDecimal("5"));

        List<BatchAllocation> result = ledger.allocateOutbound(productId, new BigDecimal("5"), warehouseId, "tester", "TEST", 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).batchNo()).isEqualTo("BATCH-001");
    }

    @Test
    void 跨批次按效期顺序依次扣减() {
        LocalDate today = LocalDate.now();
        createStock("LATE", today.plusDays(200), new BigDecimal("10"), new BigDecimal("5"));
        createStock("EARLY", today.plusDays(100), new BigDecimal("10"), new BigDecimal("5"));

        List<BatchAllocation> result = ledger.allocateOutbound(productId, new BigDecimal("15"), warehouseId, "tester", "TEST", 1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).batchNo()).isEqualTo("EARLY");
        assertThat(result.get(0).quantity()).isEqualByComparingTo("10");
        assertThat(result.get(1).batchNo()).isEqualTo("LATE");
        assertThat(result.get(1).quantity()).isEqualByComparingTo("5");

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "EARLY", warehouseId).get().getQuantity())
                .isEqualByComparingTo("0");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "LATE", warehouseId).get().getQuantity())
                .isEqualByComparingTo("5");
    }
}
