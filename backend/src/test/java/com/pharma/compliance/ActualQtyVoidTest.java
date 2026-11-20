package com.pharma.compliance;

import com.pharma.base.entity.Warehouse;
import com.pharma.common.WmsCallbackRequest;
import com.pharma.inventory.dto.StockTransferCreateRequest;
import com.pharma.inventory.dto.StockTransferItemRequest;
import com.pharma.inventory.dto.StockTransferVO;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemVO;
import com.pharma.purchase.dto.PurchaseReceiveVO;
import com.pharma.purchase.dto.PurchaseReturnCreateRequest;
import com.pharma.purchase.dto.PurchaseReturnItemRequest;
import com.pharma.purchase.dto.PurchaseReturnVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-1 执行型单据实量口径回归测试（真实 PG + 事务回滚）：
 * 1) 部分实量入库后作废只冲回 actualQty，订单进度按实量重算；
 * 2) 部分实量退货后作废只回补 actualQty（旧逻辑按单据量回补会虚增库存）；
 * 3) 部分实量调拨后作废只双向冲回 actualQty（旧逻辑按单据量会因调入仓不足无法作废）。
 * 同时覆盖「实量为 0 的行执行时未落库存/追溯，作废必须跳过」的边界。
 */
class ActualQtyVoidTest extends AbstractComplianceTest {

    /** 业务日期统一取当天（测试内不跨日）。 */
    private static final LocalDate TODAY = LocalDate.now();

    /**
     * 部分实量入库：两行共 10（6 + 4），WMS 回传 6 + 0。
     * 断言订单进度按实量判为 PARTIAL_RECEIVED，作废只冲回实量 6 并跳过实量 0 的行。
     */
    @Test
    void 部分实量入库后作废按实量冲回且订单进度按实量() {
        String batchA = "AQ-IN-A-" + System.nanoTime();
        String batchB = "AQ-IN-B-" + System.nanoTime();
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, TODAY,
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("5")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());

        PurchaseReceiveVO receive = purchaseReceiveService.create(new PurchaseReceiveCreateRequest(warehouseId,
                po.id(), TODAY, List.of(
                new PurchaseReceiveItemRequest(productId, new BigDecimal("6"), batchA,
                        TODAY.minusDays(10), TODAY.plusDays(180), new BigDecimal("5"), "INS-AQ-A"),
                new PurchaseReceiveItemRequest(productId, new BigDecimal("4"), batchB,
                        TODAY.minusDays(10), TODAY.plusDays(180), new BigDecimal("5"), "INS-AQ-B"))));
        purchaseReceiveService.submit(receive.id());
        approveAsApprover("PURCHASE_RECEIVE", receive.id());

        // 按批号定位明细主键，回传 A=6（实到）、B=0（未到）
        Map<String, Long> itemIds = purchaseReceiveService.get(receive.id()).items().stream()
                .collect(Collectors.toMap(PurchaseReceiveItemVO::batchNo, PurchaseReceiveItemVO::id));
        purchaseReceiveService.wmsCallback(receive.id(), new WmsCallbackRequest("WMS-AQ", null, List.of(
                new WmsCallbackRequest.Item(itemIds.get(batchA), new BigDecimal("6")),
                new WmsCallbackRequest.Item(itemIds.get(batchB), BigDecimal.ZERO))));

        // 库存按实量落账：只入 A 批 6，B 批实量 0 不落库存；明细实量/差异留痕
        assertThat(stockQty(warehouseId, batchA)).isEqualByComparingTo("6");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batchB, warehouseId)).isEmpty();
        Map<String, BigDecimal> actualQty = purchaseReceiveService.get(receive.id()).items().stream()
                .collect(Collectors.toMap(PurchaseReceiveItemVO::batchNo, PurchaseReceiveItemVO::actualQty));
        assertThat(actualQty.get(batchA)).isEqualByComparingTo("6");
        assertThat(actualQty.get(batchB)).isEqualByComparingTo("0");
        // 订单进度按实量：实收 6 < 订购 10，必须为部分收货而不是已完成
        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("PARTIAL_RECEIVED");

        // 作废：只冲回 A 批实量 6，跳过实量 0 的 B 批（旧逻辑按单据量 6/4 冲回会库存不足/追溯缺失）
        purchaseReceiveService.voidReceive(receive.id(), "部分实量作废回归");
        assertThat(stockQty(warehouseId, batchA)).isEqualByComparingTo("0");
        assertThat(costLedgerRepository.findFirstByProductIdOrderByIdDesc(productId).orElseThrow()
                .getBalanceQty()).isEqualByComparingTo("0");
        // 作废后无有效入库，订单由 PARTIAL_RECEIVED 回退到 APPROVED
        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("APPROVED");
    }

    /**
     * 部分实量退货：退货 4，WMS 回传实退 2。
     * 作废只回补实退 2（8 + 2 = 10），旧逻辑按单据量 4 回补会虚增到 12。
     */
    @Test
    void 部分实量退货后作废按实量回补() {
        String batch = "AQ-PR-" + System.nanoTime();
        PurchaseReceiveVO receive = receivedFull(batch, new BigDecimal("10"));

        PurchaseReturnVO ret = purchaseReturnService.create(new PurchaseReturnCreateRequest(receive.id(), supplierId,
                TODAY, "部分实退作废回归",
                List.of(new PurchaseReturnItemRequest(productId, batch, new BigDecimal("4"), warehouseId))));
        purchaseReturnService.submit(ret.id());
        approveAsApprover("PURCHASE_RETURN", ret.id());
        Long itemId = purchaseReturnService.get(ret.id()).items().get(0).id();
        purchaseReturnService.wmsCallback(ret.id(), new WmsCallbackRequest("WMS-AQ-PR", null,
                List.of(new WmsCallbackRequest.Item(itemId, new BigDecimal("2")))));

        // 实退 2：库存 10 - 2 = 8
        assertThat(stockQty(warehouseId, batch)).isEqualByComparingTo("8");
        purchaseReturnService.voidReturn(ret.id(), "部分实退作废回归");
        // 只回补实退 2：8 + 2 = 10（旧逻辑按单据量 4 回补会虚增到 12）
        assertThat(stockQty(warehouseId, batch)).isEqualByComparingTo("10");
    }

    /**
     * 部分实量调拨：调拨 10，WMS 回传实调 6。
     * 作废只双向冲回 6（调入仓 6 → 0，调出仓 4 + 6 = 10）；
     * 旧逻辑按单据量 10 扣调入仓会因库存仅 6 直接报「库存不足」无法作废。
     */
    @Test
    void 部分实量调拨后作废按实量双向冲回() {
        String batch = "AQ-TR-" + System.nanoTime();
        Long toWarehouseId = newWarehouse("实量作废目标仓", "ROOM_TEMP");
        receivedFull(batch, new BigDecimal("10"));

        StockTransferVO transfer = stockTransferService.create(new StockTransferCreateRequest(warehouseId,
                toWarehouseId, TODAY, "部分实调作废回归",
                List.of(new StockTransferItemRequest(productId, batch, new BigDecimal("10")))));
        stockTransferService.submit(transfer.id());
        approveAsApprover("STOCK_TRANSFER", transfer.id());
        Long itemId = stockTransferService.get(transfer.id()).items().get(0).id();
        stockTransferService.wmsCallback(transfer.id(), new WmsCallbackRequest("WMS-AQ-TR", null,
                List.of(new WmsCallbackRequest.Item(itemId, new BigDecimal("6")))));

        // 实调 6：调出仓 10 - 6 = 4，调入仓 6
        assertThat(stockQty(warehouseId, batch)).isEqualByComparingTo("4");
        assertThat(stockQty(toWarehouseId, batch)).isEqualByComparingTo("6");
        stockTransferService.voidTransfer(transfer.id(), "部分实调作废回归");
        // 只冲回实调 6：调入仓 0，调出仓 4 + 6 = 10
        assertThat(stockQty(toWarehouseId, batch)).isEqualByComparingTo("0");
        assertThat(stockQty(warehouseId, batch)).isEqualByComparingTo("10");
    }

    /** 采购入库全量执行（按单据量回传），作为退货/调拨用例的前置库存。 */
    private PurchaseReceiveVO receivedFull(String batch, BigDecimal qty) {
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, TODAY,
                List.of(new PurchaseOrderItemRequest(productId, qty, new BigDecimal("5")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        return receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), TODAY,
                List.of(new PurchaseReceiveItemRequest(productId, qty, batch,
                        TODAY.minusDays(10), TODAY.plusDays(180), new BigDecimal("5"), "INS-AQ"))));
    }

    /** 新建启用仓库（不同温控仓用于调拨/作废目标仓）。 */
    private Long newWarehouse(String name, String tempType) {
        Warehouse w = new Warehouse();
        w.setName(name + "-" + System.nanoTime());
        w.setTempType(tempType);
        w.setStatus("ACTIVE");
        return warehouseRepository.save(w).getId();
    }

    /** 指定仓库内某批次的当前库存数量。 */
    private BigDecimal stockQty(Long locationId, String batchNo) {
        return stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batchNo, locationId)
                .orElseThrow().getQuantity();
    }
}
