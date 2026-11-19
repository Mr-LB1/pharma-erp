package com.pharma.compliance;

import com.pharma.approval.dto.ApprovalPendingVO;
import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.common.WmsCallbackRequest;
import com.pharma.finance.repository.ApInvoiceRepository;
import com.pharma.finance.repository.ArInvoiceRepository;
import com.pharma.inventory.dto.StockTransferCreateRequest;
import com.pharma.inventory.dto.StockTransferItemRequest;
import com.pharma.inventory.dto.StockTransferVO;
import com.pharma.inventory.service.StockTransferService;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
import com.pharma.purchase.dto.PurchaseReceiveVO;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次十四：四类执行型单据的审批 + WMS 回传流（采购入库 / 销售出库 / 采购退货 / 仓库调拨）。
 * 状态机：DRAFT → SUBMITTED → APPROVED → PUSHED（下发 WMS）→ COMPLETED（回传实量后执行）。
 * 核心约束：create/approve 都不动库存与财务；只有 WMS 回传才按实量执行；重复回传幂等拒绝。
 */
class DocumentApprovalFlowTest extends AbstractComplianceTest {

    @Autowired
    private PurchaseReturnService purchaseReturnService;
    @Autowired
    private StockTransferService stockTransferService;
    @Autowired
    private ApInvoiceRepository apInvoiceRepository;
    @Autowired
    private ArInvoiceRepository arInvoiceRepository;

    private static final String BATCH = "AF-" + System.nanoTime();

    @Test
    @DisplayName("采购入库：create 不动库存 → approve 仅 PUSHED → 回传实收后才入库+应付")
    void purchaseReceiveNeedsApprovalAndWmsCallback() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-RC";
        PurchaseOrderVO po = approvedOrder(new BigDecimal("10"), new BigDecimal("5"), "maker-rc");

        PurchaseReceiveVO created = callAs("maker-rc", List.of(), () -> purchaseReceiveService.create(
                new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                        List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                                today.minusDays(5), today.plusDays(180), new BigDecimal("5"), "INS-AF")))));
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(stockQty(batch)).isNull();
        assertThat(apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", created.id())).isEmpty();
        assertThat(callAs("maker-rc", List.of(), () -> purchaseOrderService.get(po.id())).status())
                .isEqualTo("APPROVED");

        callAs("maker-rc", List.of(), () -> purchaseReceiveService.submit(created.id()));

        // 待审列表可见（APPROVER 角色）
        PageResult<ApprovalPendingVO> pending = callAs("approver", List.of("ROLE_APPROVER"),
                () -> approvalService.pending(1, 200));
        Set<String> pendingTypes = pending.getRecords().stream()
                .map(ApprovalPendingVO::bizType).collect(Collectors.toSet());
        assertThat(pendingTypes).contains("PURCHASE_RECEIVE");

        approveAsApprover("PURCHASE_RECEIVE", created.id());

        // 审批通过：仅 PUSHED，不动库存与应付
        PurchaseReceiveVO pushed = purchaseReceiveService.get(created.id());
        assertThat(pushed.status()).isEqualTo("PUSHED");
        assertThat(pushed.wmsPushedAt()).isNotNull();
        assertThat(stockQty(batch)).isNull();
        assertThat(apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", created.id())).isEmpty();
        assertThat(callAs("maker-rc", List.of(), () -> purchaseOrderService.get(po.id())).status())
                .isEqualTo("APPROVED");

        // 重复审批幂等拒绝
        assertThatThrownBy(() -> approveAsApprover("PURCHASE_RECEIVE", created.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("仅已提交状态可审批"));

        PurchaseReceiveVO done = purchaseReceiveService.wmsCallback(created.id(),
                new WmsCallbackRequest("WMS-RC-1", null, null));
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.wmsNo()).isEqualTo("WMS-RC-1");
        assertThat(done.wmsCallbackAt()).isNotNull();
        assertThat(stockQty(batch)).isEqualByComparingTo("10");
        assertThat(done.items().get(0).actualQty()).isEqualByComparingTo("10");
        assertThat(done.items().get(0).qtyDiff()).isEqualByComparingTo("0");
        assertThat(apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", created.id())).hasSize(1);
        assertThat(callAs("maker-rc", List.of(), () -> purchaseOrderService.get(po.id())).status())
                .isEqualTo("COMPLETED");

        // 重复回传：幂等拒绝，库存不重复增加
        assertThatThrownBy(() -> purchaseReceiveService.wmsCallback(created.id(), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("仅已下发 WMS 的单据可回传"));
        assertThat(stockQty(batch)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("WMS 实收少于单据量：按实收入库、差异留痕、金额按实收重算")
    void wmsCallbackUsesActualQuantity() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-DIFF";
        PurchaseOrderVO po = approvedOrder(new BigDecimal("10"), new BigDecimal("5"), "maker-diff");

        PurchaseReceiveVO created = callAs("maker-diff", List.of(), () -> purchaseReceiveService.create(
                new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                        List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                                today.minusDays(5), today.plusDays(180), new BigDecimal("5"), "INS-DIFF")))));
        callAs("maker-diff", List.of(), () -> purchaseReceiveService.submit(created.id()));
        approveAsApprover("PURCHASE_RECEIVE", created.id());

        Long itemId = purchaseReceiveService.get(created.id()).items().get(0).id();
        PurchaseReceiveVO done = purchaseReceiveService.wmsCallback(created.id(),
                new WmsCallbackRequest("WMS-DIFF", null,
                        List.of(new WmsCallbackRequest.Item(itemId, new BigDecimal("7")))));

        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(stockQty(batch)).isEqualByComparingTo("7");
        assertThat(done.items().get(0).actualQty()).isEqualByComparingTo("7");
        assertThat(done.items().get(0).qtyDiff()).isEqualByComparingTo("-3");
        // 应付按实收金额（7 × 5 = 35）生成
        assertThat(apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", created.id()))
                .singleElement().satisfies(ap -> assertThat(ap.getInvoiceAmount()).isEqualByComparingTo("35.00"));
    }

    @Test
    @DisplayName("销售出库：create/approve 不扣库存，回传实发后 FEFO 出库并生成应收")
    void salesDeliveryNeedsApprovalAndWmsCallback() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-DO";
        receiveStock(batch, new BigDecimal("10"), new BigDecimal("5"));

        SalesOrderVO so = approvedSalesOrder(new BigDecimal("6"), new BigDecimal("20"), "maker-do");

        SalesDeliveryVO created = callAs("maker-do", List.of(), () -> salesDeliveryService.create(
                new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                        List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("6"))))));
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(stockQty(batch)).isEqualByComparingTo("10");
        assertThat(arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", created.id())).isEmpty();

        callAs("maker-do", List.of(), () -> salesDeliveryService.submit(created.id()));
        approveAsApprover("SALES_DELIVERY", created.id());
        assertThat(salesDeliveryService.get(created.id()).status()).isEqualTo("PUSHED");
        assertThat(stockQty(batch)).isEqualByComparingTo("10");

        SalesDeliveryVO done = salesDeliveryService.wmsCallback(created.id(),
                new WmsCallbackRequest("WMS-DO-1", null, null));
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.wmsNo()).isEqualTo("WMS-DO-1");
        assertThat(stockQty(batch)).isEqualByComparingTo("4");
        assertThat(done.items()).hasSize(1);
        assertThat(done.items().get(0).batchNo()).isEqualTo(batch);
        assertThat(done.items().get(0).actualQty()).isEqualByComparingTo("6");
        assertThat(arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", created.id())).hasSize(1);
        assertThat(callAs("maker-do", List.of(), () -> salesOrderService.get(so.id())).status())
                .isEqualTo("COMPLETED");

        assertThatThrownBy(() -> salesDeliveryService.wmsCallback(created.id(), null))
                .isInstanceOf(BusinessException.class);
        assertThat(stockQty(batch)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("采购退货：create/approve 不扣库存，回传实退后才扣减并生成负数应付")
    void purchaseReturnNeedsApprovalAndWmsCallback() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-PR";
        receiveStock(batch, new BigDecimal("10"), new BigDecimal("5"));

        PurchaseReturnVO created = callAs("maker-pr", List.of(), () -> purchaseReturnService.create(
                new PurchaseReturnCreateRequest(null, supplierId, today, "质量退货",
                        List.of(new PurchaseReturnItemRequest(productId, batch, new BigDecimal("4"), warehouseId)))));
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(stockQty(batch)).isEqualByComparingTo("10");
        assertThat(apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RETURN", created.id())).isEmpty();

        callAs("maker-pr", List.of(), () -> purchaseReturnService.submit(created.id()));
        approveAsApprover("PURCHASE_RETURN", created.id());
        assertThat(purchaseReturnService.get(created.id()).status()).isEqualTo("PUSHED");
        assertThat(stockQty(batch)).isEqualByComparingTo("10");

        PurchaseReturnVO done = purchaseReturnService.wmsCallback(created.id(), null);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(stockQty(batch)).isEqualByComparingTo("6");
        assertThat(done.totalAmount()).isEqualByComparingTo("20");
        assertThat(done.items().get(0).actualQty()).isEqualByComparingTo("4");
        assertThat(apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RETURN", created.id()))
                .singleElement().satisfies(ap -> assertThat(ap.getInvoiceAmount()).isEqualByComparingTo("-20"));
    }

    @Test
    @DisplayName("仓库调拨：create/approve 不移动库存，回传实调后才 from 扣 to 增")
    void stockTransferNeedsApprovalAndWmsCallback() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-TR";
        receiveStock(batch, new BigDecimal("10"), new BigDecimal("5"));
        Long toWarehouseId = newWarehouse("审批目标仓-" + System.nanoTime());

        StockTransferVO created = callAs("maker-tr", List.of(), () -> stockTransferService.create(
                new StockTransferCreateRequest(warehouseId, toWarehouseId, today, "审批后调拨",
                        List.of(new StockTransferItemRequest(productId, batch, new BigDecimal("4"))))));
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(stockQty(batch)).isEqualByComparingTo("10");
        assertThat(stockQtyAt(batch, toWarehouseId)).isNull();

        callAs("maker-tr", List.of(), () -> stockTransferService.submit(created.id()));
        approveAsApprover("STOCK_TRANSFER", created.id());
        assertThat(stockTransferService.get(created.id()).status()).isEqualTo("PUSHED");
        assertThat(stockQty(batch)).isEqualByComparingTo("10");
        assertThat(stockQtyAt(batch, toWarehouseId)).isNull();

        StockTransferVO done = stockTransferService.wmsCallback(created.id(), null);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(stockQty(batch)).isEqualByComparingTo("6");
        assertThat(stockQtyAt(batch, toWarehouseId)).isEqualByComparingTo("4");
        assertThat(done.items().get(0).actualQty()).isEqualByComparingTo("4");

        assertThatThrownBy(() -> stockTransferService.wmsCallback(created.id(), null))
                .isInstanceOf(BusinessException.class);
        assertThat(stockQty(batch)).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("驳回后可编辑重提：REJECTED 不动库存，改数量后重提→审批→回传按新数量入库")
    void rejectedDocumentCanBeEditedAndResubmitted() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-REJ";
        PurchaseOrderVO po = approvedOrder(new BigDecimal("10"), new BigDecimal("5"), "maker-rej");

        PurchaseReceiveVO created = callAs("maker-rej", List.of(), () -> purchaseReceiveService.create(
                new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                        List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                                today.minusDays(5), today.plusDays(180), new BigDecimal("5"), "INS-REJ")))));
        callAs("maker-rej", List.of(), () -> purchaseReceiveService.submit(created.id()));
        rejectAsApprover("PURCHASE_RECEIVE", created.id(), "数量与随货同行单不符");

        assertThat(purchaseReceiveService.get(created.id()).status()).isEqualTo("REJECTED");
        assertThat(stockQty(batch)).isNull();

        PurchaseReceiveVO edited = callAs("maker-rej", List.of(), () -> purchaseReceiveService.update(
                created.id(), new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                        List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("6"), batch,
                                today.minusDays(5), today.plusDays(180), new BigDecimal("5"), "INS-REJ2")))));
        assertThat(edited.status()).isEqualTo("REJECTED");
        assertThat(edited.items().get(0).quantity()).isEqualByComparingTo("6");

        callAs("maker-rej", List.of(), () -> purchaseReceiveService.submit(created.id()));
        approveAsApprover("PURCHASE_RECEIVE", created.id());
        purchaseReceiveService.wmsCallback(created.id(), null);

        assertThat(purchaseReceiveService.get(created.id()).status()).isEqualTo("COMPLETED");
        assertThat(stockQty(batch)).isEqualByComparingTo("6");
    }

    // ==================== 辅助 ====================

    private PurchaseOrderVO approvedOrder(BigDecimal qty, BigDecimal cost, String maker) {
        LocalDate today = LocalDate.now();
        PurchaseOrderVO po = callAs(maker, List.of(), () -> purchaseOrderService.create(
                new PurchaseOrderCreateRequest(supplierId, today,
                        List.of(new PurchaseOrderItemRequest(productId, qty, cost)))));
        callAs(maker, List.of(), () -> purchaseOrderService.submit(po.id()));
        approveAsApprover("PURCHASE_ORDER", po.id());
        return po;
    }

    private SalesOrderVO approvedSalesOrder(BigDecimal qty, BigDecimal price, String maker) {
        LocalDate today = LocalDate.now();
        SalesOrderVO so = callAs(maker, List.of(), () -> salesOrderService.create(
                new SalesOrderCreateRequest(customerId, today,
                        List.of(new SalesOrderItemRequest(productId, qty, price)))));
        callAs(maker, List.of(), () -> salesOrderService.submit(so.id()));
        approveAsApprover("SALES_ORDER", so.id());
        return so;
    }

    /** 通过采购入库（含审批与 WMS 回传）备货，保证库存批次存在。 */
    private void receiveStock(String batch, BigDecimal qty, BigDecimal cost) {
        LocalDate today = LocalDate.now();
        PurchaseOrderVO po = approvedOrder(qty, cost, "wh");
        callAs("wh", List.of(), () -> receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, qty, batch,
                        today.minusDays(5), today.plusDays(180), cost, "INS-SEED")))));
    }

    private Long newWarehouse(String name) {
        com.pharma.base.entity.Warehouse w = new com.pharma.base.entity.Warehouse();
        w.setName(name);
        w.setTempType("ROOM_TEMP");
        w.setStatus("ACTIVE");
        return warehouseRepository.save(w).getId();
    }

    private BigDecimal stockQty(String batch) {
        return stockQtyAt(batch, warehouseId);
    }

    private BigDecimal stockQtyAt(String batch, Long locationId) {
        return stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, locationId)
                .map(com.pharma.inventory.entity.Stock::getQuantity).orElse(null);
    }
}
