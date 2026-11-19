package com.pharma.compliance;

import com.pharma.inventory.entity.CostLedger;
import com.pharma.inventory.entity.LotTrace;
import com.pharma.inventory.entity.StockTransaction;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
import com.pharma.purchase.dto.PurchaseReceiveVO;
import com.pharma.sales.dto.SalesDeliveryCreateRequest;
import com.pharma.sales.dto.SalesDeliveryItemRequest;
import com.pharma.sales.dto.SalesDeliveryVO;
import com.pharma.sales.dto.SalesOrderCreateRequest;
import com.pharma.sales.dto.SalesOrderItemRequest;
import com.pharma.sales.dto.SalesOrderVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.pharma.inventory.service.InventoryLedgerService.REF_PURCHASE_RECEIVE;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_PURCHASE_RECEIVE_VOID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R8 不可物理删除：作废 = status 置 VOID + 反向流水 + 反向成本 + lot_trace 作废记录，原记录仍在。
 */
class R8VoidTest extends AbstractComplianceTest {

    @Test
    void 作废入库单回滚库存且原记录保留() {
        LocalDate today = LocalDate.now();
        String batch = "V8-IN-" + System.nanoTime();

        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("10")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        PurchaseReceiveVO receive = receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                        today.minusDays(5), today.plusDays(150), new BigDecimal("5"), "INS-V8"))));

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .get().getQuantity()).isEqualByComparingTo("10");

        PurchaseReceiveVO voided = purchaseReceiveService.voidReceive(receive.id(), "测试作废入库");
        assertThat(voided.status()).isEqualTo("VOID");

        // 库存回滚为 0
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .get().getQuantity()).isEqualByComparingTo("0");

        // 反向流水存在（OUT，数量取反）
        List<StockTransaction> txns = stockTransactionRepository.findByRefTypeAndRefId(REF_PURCHASE_RECEIVE, receive.id());
        assertThat(txns).anyMatch(t -> "OUT".equals(t.getOpType()) && t.getQuantity().compareTo(BigDecimal.ZERO) < 0);

        // 成本余额回滚为 0
        CostLedger latest = costLedgerRepository.findFirstByProductIdOrderByIdDesc(productId).orElseThrow();
        assertThat(latest.getBalanceQty()).isEqualByComparingTo("0");

        // lot_trace 作废记录存在
        List<LotTrace> traces = lotTraceRepository.findByProductIdAndBatchNoOrderByIdAsc(productId, batch);
        assertThat(traces).anyMatch(t -> TRACE_PURCHASE_RECEIVE_VOID.equals(t.getTargetType()));

        // 原记录仍在（不物理删除）
        assertThat(purchaseReceiveRepository.findById(receive.id())).isPresent();
    }

    @Test
    void 作废出库单回补库存() {
        LocalDate today = LocalDate.now();
        String batch = "V8-OUT-" + System.nanoTime();

        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("10")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                        today.minusDays(5), today.plusDays(150), new BigDecimal("5"), "INS-V8"))));

        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("6"), new BigDecimal("20")))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        SalesDeliveryVO delivery = deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("6")))));

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .get().getQuantity()).isEqualByComparingTo("4");

        SalesDeliveryVO voided = salesDeliveryService.voidDelivery(delivery.id(), "测试作废出库");
        assertThat(voided.status()).isEqualTo("VOID");

        // 库存回补为 10
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .get().getQuantity()).isEqualByComparingTo("10");

        // 原出库记录仍在
        assertThat(salesDeliveryRepository.findById(delivery.id())).isPresent();
    }
}
