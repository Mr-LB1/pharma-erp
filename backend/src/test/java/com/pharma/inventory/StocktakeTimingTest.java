package com.pharma.inventory;

import com.pharma.inventory.dto.StocktakeCreateRequest;
import com.pharma.inventory.dto.StocktakeItemRequest;
import com.pharma.inventory.dto.StocktakeItemVO;
import com.pharma.inventory.dto.StocktakeVO;
import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.entity.StocktakeItem;
import com.pharma.inventory.repository.StocktakeItemRepository;
import com.pharma.inventory.repository.StockTransactionRepository;
import com.pharma.inventory.service.StocktakeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.pharma.inventory.service.InventoryLedgerService.REF_SALES_DELIVERY;
import static com.pharma.inventory.service.InventoryLedgerService.REF_STOCKTAKE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-8 盘点时序：create 时的 book/diff 只是快照，submit 必须按「提交时点」重新读取账面重算盈亏。
 * 复现原缺陷：盘点后发生出库（或入库）再提交，旧 diff 会把调账数量算错。
 */
class StocktakeTimingTest extends AbstractInventoryTest {

    @Autowired
    private StocktakeItemRepository stocktakeItemRepository;
    @Autowired
    private StocktakeService stocktakeService;
    @Autowired
    private StockTransactionRepository stockTransactionRepository;

    @Test
    void 盘点后出库再提交按提交时账面重算() {
        String batch = "ST-T1-" + System.nanoTime();
        createStock(batch, LocalDate.now().plusDays(180), new BigDecimal("100"), new BigDecimal("5"));

        // create：账面 100，实盘 40 → 快照 diff = -60
        StocktakeVO stocktake = stocktakeService.create(new StocktakeCreateRequest(warehouseId,
                List.of(new StocktakeItemRequest(productId, batch, new BigDecimal("40"), null, null))));
        assertThat(stocktake.items().get(0).diffQty()).isEqualByComparingTo("-60");

        // 盘点期间发生出库 60，账面变为 40
        ledger.allocateOutbound(productId, new BigDecimal("60"), warehouseId, "tester",
                REF_SALES_DELIVERY, 999L);
        assertThat(currentQty(batch)).isEqualByComparingTo("40");

        StocktakeVO submitted = stocktakeService.submit(stocktake.id());

        // 提交时点：actual 40 == book 40 → 无盈亏，库存保持 40（旧实现会再扣 60 变成 -20）
        assertThat(currentQty(batch)).isEqualByComparingTo("40");
        assertThat(stockTransactionRepository.findByRefTypeAndRefId(REF_STOCKTAKE, stocktake.id())).isEmpty();
        StocktakeItem item = stocktakeItemRepository.findByStocktakeId(stocktake.id()).get(0);
        assertThat(item.getCommittedBookQty()).isEqualByComparingTo("40");
        assertThat(submitted.items().get(0).actualQty()).isEqualByComparingTo("40");
    }

    @Test
    void 盘点后入库再提交按提交时账面重算() {
        String batch = "ST-T2-" + System.nanoTime();
        createStock(batch, LocalDate.now().plusDays(180), new BigDecimal("50"), new BigDecimal("5"));

        // create：账面 50，实盘 90 → 快照 diff = +40
        StocktakeVO stocktake = stocktakeService.create(new StocktakeCreateRequest(warehouseId,
                List.of(new StocktakeItemRequest(productId, batch, new BigDecimal("90"), null, null))));
        assertThat(stocktake.items().get(0).diffQty()).isEqualByComparingTo("40");

        // 盘点期间入库 10，账面变为 60
        ledger.upsertStock(productId, batch, LocalDate.now().minusDays(10), LocalDate.now().plusDays(180),
                warehouseId, new BigDecimal("10"), new BigDecimal("5"));

        stocktakeService.submit(stocktake.id());

        // 提交时点：90 - 60 = +30；最终库存为实盘 90（旧实现会变成 100）
        assertThat(currentQty(batch)).isEqualByComparingTo("90");
        StocktakeItem item = stocktakeItemRepository.findByStocktakeId(stocktake.id()).get(0);
        assertThat(item.getCommittedBookQty()).isEqualByComparingTo("60");
        assertThat(item.getCommittedDiffQty()).isEqualByComparingTo("30");
    }

    private BigDecimal currentQty(String batchNo) {
        return stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batchNo, warehouseId)
                .map(Stock::getQuantity)
                .orElse(BigDecimal.ZERO);
    }
}
