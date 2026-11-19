package com.pharma.inventory;

import com.pharma.base.entity.Warehouse;
import com.pharma.common.BusinessException;
import com.pharma.inventory.dto.StockTransferCreateRequest;
import com.pharma.inventory.dto.StockTransferItemRequest;
import com.pharma.inventory.dto.StockTransferVO;
import com.pharma.inventory.entity.StockTransaction;
import com.pharma.inventory.repository.StockTransactionRepository;
import com.pharma.inventory.service.StockTransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.pharma.inventory.service.InventoryLedgerService.REF_TRANSFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 仓库调拨（P1-12）：一事务一减一增两条流水（ref_type=TRANSFER），无负库存。
 */
class StockTransferTest extends AbstractInventoryTest {

    @Autowired
    private StockTransferService stockTransferService;
    @Autowired
    private StockTransactionRepository stockTransactionRepository;

    private static final String BATCH = "TR-" + System.nanoTime();

    @Test
    void 调拨一减一增两条流水且成本不变() {
        LocalDate today = LocalDate.now();
        Long toWarehouseId = newWarehouse("测试仓B");
        createStock(BATCH, today.plusDays(150), new BigDecimal("10"), new BigDecimal("6"));

        StockTransferVO transfer = stockTransferService.create(new StockTransferCreateRequest(
                warehouseId, toWarehouseId, today, "仓间移库",
                List.of(new StockTransferItemRequest(productId, BATCH, new BigDecimal("4")))));

        // 批次十四：建单只记草稿，不执行移库
        assertThat(transfer.status()).isEqualTo("DRAFT");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, toWarehouseId))
                .isEmpty();

        stockTransferService.submit(transfer.id());
        approveAsApprover("STOCK_TRANSFER", transfer.id());
        // 审批仅下发 WMS（PUSHED），回传后才实际移库
        assertThat(stockTransferService.get(transfer.id()).status()).isEqualTo("PUSHED");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
        wmsCallbackFull("STOCK_TRANSFER", transfer.id());
        transfer = stockTransferService.get(transfer.id());

        assertThat(transfer.status()).isEqualTo("COMPLETED");
        assertThat(transfer.transferNo()).startsWith("TR");
        assertThat(transfer.items()).hasSize(1);

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("6");
        var toStock = stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, toWarehouseId)
                .orElseThrow();
        assertThat(toStock.getQuantity()).isEqualByComparingTo("4");
        assertThat(toStock.getUnitCost()).isEqualByComparingTo("6");

        List<StockTransaction> txns = stockTransactionRepository.findByRefTypeAndRefId(REF_TRANSFER, transfer.id());
        assertThat(txns).hasSize(2);
        assertThat(txns).anyMatch(t -> "OUT".equals(t.getOpType()) && t.getQuantity().compareTo(BigDecimal.ZERO) < 0
                && warehouseId.equals(t.getLocationId()));
        assertThat(txns).anyMatch(t -> "IN".equals(t.getOpType()) && t.getQuantity().compareTo(BigDecimal.ZERO) > 0
                && toWarehouseId.equals(t.getLocationId()));
    }

    @Test
    void 调拨量超过调出仓库存被拒绝且无负库存() {
        LocalDate today = LocalDate.now();
        Long toWarehouseId = newWarehouse("测试仓C");
        createStock(BATCH, today.plusDays(150), new BigDecimal("5"), new BigDecimal("6"));

        assertThatThrownBy(() -> stockTransferService.create(new StockTransferCreateRequest(
                warehouseId, toWarehouseId, today, "超量调拨",
                List.of(new StockTransferItemRequest(productId, BATCH, new BigDecimal("8"))))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("5");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, toWarehouseId))
                .isEmpty();
    }

    private Long newWarehouse(String name) {
        Warehouse w = new Warehouse();
        w.setName(name);
        w.setTempType("ROOM_TEMP");
        w.setStatus("ACTIVE");
        return warehouseRepository.save(w).getId();
    }
}
