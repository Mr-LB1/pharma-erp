package com.pharma.compliance;

import com.pharma.base.entity.Warehouse;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.common.BusinessException;
import com.pharma.inventory.dto.FefoPreviewVO;
import com.pharma.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #13 FEFO 事前预览集成测试：零副作用（不改库存、不写流水、不写成本台账）、
 * 近效期阻断、混合批次计划、停用仓库 400、数量非正 400。
 */
class FefoPreviewTest extends AbstractComplianceTest {

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private WarehouseRepository warehouseRepo;

    @Test
    void 预览零副作用() {
        LocalDate today = LocalDate.now();
        createStock("A", today.plusDays(100), new BigDecimal("10"), new BigDecimal("5"));
        createStock("B", today.plusDays(200), new BigDecimal("10"), new BigDecimal("5"));

        BigDecimal qtyA = stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "A", warehouseId)
                .orElseThrow().getQuantity();
        long txBefore = stockTransactionRepository.count();
        long costBefore = costLedgerRepository.count();

        FefoPreviewVO preview = inventoryService.fefoPreview(productId, warehouseId, new BigDecimal("15"));

        assertThat(preview.blocked()).isFalse();
        assertThat(preview.totalSellable()).isEqualByComparingTo("20");
        assertThat(preview.batches()).hasSize(2);
        assertThat(stockTransactionRepository.count()).isEqualTo(txBefore);
        assertThat(costLedgerRepository.count()).isEqualTo(costBefore);
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "A", warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo(qtyA);
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, "A", warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void 仅近效期批次时阻断且可售总量为零() {
        LocalDate today = LocalDate.now();
        createStock("NEAR", today.plusDays(10), new BigDecimal("10"), new BigDecimal("5"));

        FefoPreviewVO preview = inventoryService.fefoPreview(productId, warehouseId, new BigDecimal("1"));

        assertThat(preview.blocked()).isTrue();
        assertThat(preview.totalSellable()).isEqualByComparingTo("0");
        assertThat(preview.blockReason()).isNotBlank();
        assertThat(preview.batches()).hasSize(1);
        assertThat(preview.batches().get(0).plannedQty()).isEqualByComparingTo("0");
        assertThat(preview.batches().get(0).expireStatus()).isEqualTo("NEAR_EXPIRY");
    }

    @Test
    void 混合批次按FEFO给出计划量() {
        LocalDate today = LocalDate.now();
        createStock("LATE", today.plusDays(300), new BigDecimal("10"), new BigDecimal("5"));
        createStock("EARLY", today.plusDays(100), new BigDecimal("10"), new BigDecimal("5"));
        createStock("EXPIRED", today.minusDays(1), new BigDecimal("10"), new BigDecimal("5"));

        FefoPreviewVO preview = inventoryService.fefoPreview(productId, warehouseId, new BigDecimal("15"));

        assertThat(preview.requestedQty()).isEqualByComparingTo("15");
        assertThat(preview.nearExpiryDays()).isEqualTo(30);
        assertThat(preview.productName()).isEqualTo("测试药品");
        assertThat(preview.batches()).extracting(FefoPreviewVO.Batch::batchNo)
                .containsExactly("EXPIRED", "EARLY", "LATE");
        assertThat(preview.batches()).extracting(FefoPreviewVO.Batch::plannedQty)
                .containsExactly(BigDecimal.ZERO, new BigDecimal("10"), new BigDecimal("5"));
        assertThat(preview.batches().get(0).daysToExpire()).isEqualTo(-1L);
        assertThat(preview.batches().get(0).expireStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void 停用仓库返回400() {
        Warehouse inactive = new Warehouse();
        inactive.setName("停用仓");
        inactive.setTempType("ROOM_TEMP");
        inactive.setStatus("INACTIVE");
        Long inactiveId = warehouseRepo.save(inactive).getId();

        assertThatThrownBy(() -> inventoryService.fefoPreview(productId, inactiveId, new BigDecimal("1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 仓库不存在返回400() {
        assertThatThrownBy(() -> inventoryService.fefoPreview(productId, 99999999L, new BigDecimal("1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 数量非正返回400() {
        assertThatThrownBy(() -> inventoryService.fefoPreview(productId, warehouseId, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
        assertThatThrownBy(() -> inventoryService.fefoPreview(productId, warehouseId, new BigDecimal("-1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
        assertThatThrownBy(() -> inventoryService.fefoPreview(productId, warehouseId, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }
}
