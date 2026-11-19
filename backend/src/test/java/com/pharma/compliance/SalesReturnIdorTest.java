package com.pharma.compliance;

import com.pharma.base.entity.Warehouse;
import com.pharma.common.PageResult;
import com.pharma.inventory.dto.StockTransferCreateRequest;
import com.pharma.inventory.dto.StockTransferItemRequest;
import com.pharma.inventory.dto.StockTransferVO;
import com.pharma.inventory.service.StockTransferService;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
import com.pharma.purchase.dto.PurchaseReturnCreateRequest;
import com.pharma.purchase.dto.PurchaseReturnItemRequest;
import com.pharma.purchase.dto.PurchaseReturnVO;
import com.pharma.purchase.service.PurchaseReturnService;
import com.pharma.sales.dto.SalesDeliveryCreateRequest;
import com.pharma.sales.dto.SalesDeliveryItemRequest;
import com.pharma.sales.dto.SalesDeliveryVO;
import com.pharma.sales.dto.SalesOrderCreateRequest;
import com.pharma.sales.dto.SalesOrderItemRequest;
import com.pharma.sales.dto.SalesOrderVO;
import com.pharma.sales.dto.SalesReturnCreateRequest;
import com.pharma.sales.dto.SalesReturnItemRequest;
import com.pharma.sales.dto.SalesReturnReceiveItemRequest;
import com.pharma.sales.dto.SalesReturnReceiveRequest;
import com.pharma.sales.dto.SalesReturnVO;
import com.pharma.sales.service.SalesReturnService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次十（决策 1）：退货/调拨不再按 createdBy 隔离。
 * 退货由「退货专员」角色按权限码负责全仓退货单：可查看/提交/验收他人创建的退货；
 * 列表返回全量（可见性由 sales:return:view / purchase:return:view / inventory:transfer:view 控制）。
 */
class SalesReturnIdorTest extends AbstractComplianceTest {

    @Autowired
    private SalesReturnService salesReturnService;
    @Autowired
    private PurchaseReturnService purchaseReturnService;
    @Autowired
    private StockTransferService stockTransferService;

    private static final String BATCH_A = "IDOR-A-" + System.nanoTime();
    private static final String BATCH_B = "IDOR-B-" + System.nanoTime();

    private static final List<String> RETURN_SPECIALIST = List.of(
            "sales:return:view", "sales:return:create", "sales:return:receive",
            "purchase:return:view", "purchase:return:create", "inventory:stock:view");

    @Test
    @DisplayName("销售退货：退货专员可提交/验收获他人退货，列表不按制单人隔离")
    void returnSpecialistCanHandleOthersReturns() {
        LocalDate today = LocalDate.now();
        SalesDeliveryVO deliveryA = delivered("sales-a", BATCH_A);
        SalesReturnVO returnA = callAs("sales-a", List.of(), () -> salesReturnService.create(
                new SalesReturnCreateRequest(deliveryA.id(), null, today, "客户退货",
                        List.of(new SalesReturnItemRequest(productId, BATCH_A, new BigDecimal("2"), warehouseId)))));

        // 他人（退货专员）提交 → 允许
        callAs("return-specialist", RETURN_SPECIALIST, () -> salesReturnService.submit(returnA.id()));
        approveAsApprover("SALES_RETURN", returnA.id());

        // 他人（退货专员）验收 → 允许
        SalesReturnVO received = callAs("return-specialist", RETURN_SPECIALIST,
                () -> salesReturnService.receive(returnA.id(), new SalesReturnReceiveRequest(List.of(
                        new SalesReturnReceiveItemRequest(returnA.items().get(0).id(), true, null)))));
        assertThat(received.status()).isEqualTo("RECEIVED");

        // 列表不隔离：任一持 view 权限的退货专员都能看到该单
        List<Long> idsForSpecialist = callAs("return-specialist", List.of("sales:return:view"),
                () -> ids(salesReturnService.list(null, null, 1, 200)));
        assertThat(idsForSpecialist).contains(returnA.id());
        List<Long> idsForOther = callAs("sales-b", List.of("sales:return:view"),
                () -> ids(salesReturnService.list(null, null, 1, 200)));
        assertThat(idsForOther).contains(returnA.id());
    }

    @Test
    @DisplayName("采购退货/仓库调拨：列表不按制单人隔离，全部可见")
    void purchaseReturnAndTransferListScope() {
        LocalDate today = LocalDate.now();
        createStock(BATCH_B, today.plusDays(200), new BigDecimal("50"), new BigDecimal("4"));
        Long toWarehouseId = newWarehouse("越权测试仓B");

        PurchaseReturnVO prA = callAs("buyer-a", List.of(), () -> purchaseReturnService.create(
                new PurchaseReturnCreateRequest(null, supplierId, today, "质量退货",
                        List.of(new PurchaseReturnItemRequest(productId, BATCH_B, new BigDecimal("2"), warehouseId)))));
        PurchaseReturnVO prB = callAs("buyer-b", List.of(), () -> purchaseReturnService.create(
                new PurchaseReturnCreateRequest(null, supplierId, today, "质量退货",
                        List.of(new PurchaseReturnItemRequest(productId, BATCH_B, new BigDecimal("3"), warehouseId)))));

        List<Long> prForA = callAs("buyer-a", List.of("purchase:return:view"),
                () -> ids2(purchaseReturnService.list(null, null, 1, 200)));
        assertThat(prForA).contains(prA.id(), prB.id());

        // 调拨：A/B 各建一单，任一仓管可见两单
        createStock(BATCH_A, today.plusDays(200), new BigDecimal("50"), new BigDecimal("4"));
        StockTransferVO trA = callAs("wh-a", List.of(), () -> stockTransferService.create(
                new StockTransferCreateRequest(warehouseId, toWarehouseId, today, "A 调拨",
                        List.of(new StockTransferItemRequest(productId, BATCH_A, new BigDecimal("1"))))));
        StockTransferVO trB = callAs("wh-b", List.of(), () -> stockTransferService.create(
                new StockTransferCreateRequest(warehouseId, toWarehouseId, today, "B 调拨",
                        List.of(new StockTransferItemRequest(productId, BATCH_A, new BigDecimal("1"))))));

        List<Long> trForA = callAs("wh-a", List.of("inventory:transfer:view"),
                () -> ids3(stockTransferService.list(null, 1, 200)));
        assertThat(trForA).contains(trA.id(), trB.id());
    }

    /** 以 user 身份完成 采购入库 → 销售出库，制造可退批次。 */
    private SalesDeliveryVO delivered(String user, String batch) {
        LocalDate today = LocalDate.now();
        PurchaseOrderVO po = callAs(user, List.of(), () -> purchaseOrderService.create(
                new PurchaseOrderCreateRequest(supplierId, today,
                        List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("5"))))));
        callAs(user, List.of(), () -> purchaseOrderService.submit(po.id()));
        approveAsApprover("PURCHASE_ORDER", po.id());
        callAs(user, List.of(), () -> receiveApproved(
                new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                        List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                                today.minusDays(10), today.plusDays(180), new BigDecimal("5"), "INS-IDOR")))));

        SalesOrderVO so = callAs(user, List.of(), () -> salesOrderService.create(
                new SalesOrderCreateRequest(customerId, today,
                        List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20"))))));
        callAs(user, List.of(), () -> salesOrderService.submit(so.id()));
        approveAsApprover("SALES_ORDER", so.id());
        return callAs(user, List.of(), () -> deliveryApproved(
                new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                        List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("10"))))));
    }

    private Long newWarehouse(String name) {
        Warehouse w = new Warehouse();
        w.setName(name);
        w.setTempType("ROOM_TEMP");
        w.setStatus("ACTIVE");
        return warehouseRepository.save(w).getId();
    }

    private List<Long> ids(PageResult<SalesReturnVO> page) {
        return page.getRecords().stream().map(SalesReturnVO::id).toList();
    }

    private List<Long> ids2(PageResult<PurchaseReturnVO> page) {
        return page.getRecords().stream().map(PurchaseReturnVO::id).toList();
    }

    private List<Long> ids3(PageResult<StockTransferVO> page) {
        return page.getRecords().stream().map(StockTransferVO::id).toList();
    }
}
