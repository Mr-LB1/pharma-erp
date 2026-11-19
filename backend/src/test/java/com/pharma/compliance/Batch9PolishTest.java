package com.pharma.compliance;

import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Product;
import com.pharma.base.entity.Warehouse;
import com.pharma.base.service.ProductService;
import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.finance.entity.ApInvoice;
import com.pharma.finance.repository.ApInvoiceRepository;
import com.pharma.finance.service.ArApService;
import com.pharma.inventory.dto.StockTransferCreateRequest;
import com.pharma.inventory.dto.StockTransferItemRequest;
import com.pharma.inventory.dto.StockTransferVO;
import com.pharma.inventory.entity.StockTransaction;
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
import com.pharma.reports.dto.StockSummaryVO;
import com.pharma.reports.service.ReportService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.pharma.inventory.service.InventoryLedgerService.OP_IN;
import static com.pharma.inventory.service.InventoryLedgerService.REF_PURCHASE_RECEIVE;
import static com.pharma.inventory.service.InventoryLedgerService.REF_PURCHASE_RETURN;
import static com.pharma.inventory.service.InventoryLedgerService.REF_TRANSFER;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_PURCHASE_RETURN_VOID;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_TRANSFER_VOID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次九打磨回归（P2-7~11）：报表分页、出库信用二次校验、温控兼容、首营本人回避、调拨/采购退货作废。
 */
class Batch9PolishTest extends AbstractComplianceTest {

    @Autowired
    private ReportService reportService;
    @Autowired
    private ProductService productService;
    @Autowired
    private StockTransferService stockTransferService;
    @Autowired
    private PurchaseReturnService purchaseReturnService;
    @Autowired
    private SalesReturnService salesReturnService;
    @Autowired
    private ArApService arApService;
    @Autowired
    private ApInvoiceRepository apInvoiceRepository;

    // ==================== P2-7 报表分页 ====================

    @Test
    void 进销存汇总分页返回total与当页记录() {
        String batch = "RPT-" + System.nanoTime();
        createStock(batch, LocalDate.now().plusDays(180), new BigDecimal("10"), new BigDecimal("5"));
        ledger.writeStockTransaction(productId, batch, OP_IN, REF_PURCHASE_RECEIVE, 900001L,
                warehouseId, new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"), "tester");

        PageResult<StockSummaryVO> page = reportService.stockSummaryPage(null, null, null, null, 1, 1);

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(1);
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(page.getRecords()).hasSize(1);
        assertThat(reportService.stockSummary(null, null, null, null)).isNotEmpty();
    }

    // ==================== P2-8 出库信用二次校验 ====================

    @Test
    void 出库时未结应收加本次出库超信用额度被拒绝400() {
        LocalDate today = LocalDate.now();
        Customer customer = customerRepository.findById(customerId).orElseThrow();
        customer.setCreditLimit(new BigDecimal("600"));
        customerRepository.save(customer);

        String batch = "CR-" + System.nanoTime();
        createStock(batch, today.plusDays(180), new BigDecimal("10"), new BigDecimal("5"));

        SalesOrderVO order = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20")))));
        salesOrderService.submit(order.id());
        approveAsApprover("SALES_ORDER", order.id());

        // 出库前新增 500 未结应收占额度：200 + 500 > 600
        arApService.createManualAr(customerId, new BigDecimal("500"), "占用额度");

        assertThatThrownBy(() -> salesDeliveryService.create(new SalesDeliveryCreateRequest(warehouseId,
                order.id(), today, List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("10"))))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("超出信用额度（出库二次校验）");
                });

        // 库存未被扣减（校验在出库前）
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
    }

    // ==================== P2-9 温控兼容 ====================

    @Test
    void 常温商品调拨到冷藏仓被拒绝400() {
        LocalDate today = LocalDate.now();
        Product product = productRepository.findById(productId).orElseThrow();
        product.setStorageCond("ROOM_TEMP");
        productRepository.save(product);
        Long coldWarehouseId = newWarehouse("冷藏仓", "COLD");
        String batch = "TEMP-TR-" + System.nanoTime();
        createStock(batch, today.plusDays(180), new BigDecimal("10"), new BigDecimal("5"));

        assertThatThrownBy(() -> stockTransferService.create(new StockTransferCreateRequest(warehouseId,
                coldWarehouseId, today, "温控校验",
                List.of(new StockTransferItemRequest(productId, batch, new BigDecimal("2"))))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("温控不兼容");
                });
    }

    @Test
    void 销售退货合格回补到不兼容仓被拒绝400() {
        LocalDate today = LocalDate.now();
        Product product = productRepository.findById(productId).orElseThrow();
        product.setStorageCond("ROOM_TEMP");
        productRepository.save(product);
        Long coldWarehouseId = newWarehouse("冷藏回补仓", "COLD");

        String batch = "TEMP-SR-" + System.nanoTime();
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("5")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                        today.minusDays(5), today.plusDays(180), new BigDecimal("5"), "INS-TEMP"))));

        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20")))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        SalesDeliveryVO delivery = deliveryApproved(new SalesDeliveryCreateRequest(warehouseId,
                so.id(), today, List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("10")))));

        SalesReturnVO ret = salesReturnService.create(new SalesReturnCreateRequest(delivery.id(), null, today,
                "退货至冷藏仓", List.of(new SalesReturnItemRequest(productId, batch, new BigDecimal("2"),
                coldWarehouseId))));
        salesReturnService.submit(ret.id());
        approveAsApprover("SALES_RETURN", ret.id());

        assertThatThrownBy(() -> salesReturnService.receive(ret.id(), new SalesReturnReceiveRequest(
                List.of(new SalesReturnReceiveItemRequest(ret.items().get(0).id(), true, null)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("温控不兼容");
                });
    }

    @Test
    void 冷藏商品从常温仓采购退货被拒绝400() {
        LocalDate today = LocalDate.now();
        Product product = productRepository.findById(productId).orElseThrow();
        product.setStorageCond("COLD");
        productRepository.save(product);
        String batch = "TEMP-PR-" + System.nanoTime();
        createStock(batch, today.plusDays(180), new BigDecimal("10"), new BigDecimal("5"));

        assertThatThrownBy(() -> purchaseReturnService.create(new PurchaseReturnCreateRequest(null, supplierId,
                today, "温控校验退货",
                List.of(new PurchaseReturnItemRequest(productId, batch, new BigDecimal("2"), warehouseId)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("温控不兼容");
                });
    }

    // ==================== P2-10 首营本人回避 ====================

    @Test
    void 首营资料创建人不能审核本人资料403() {
        Product body = new Product();
        body.setCode("SELF-" + System.nanoTime());
        body.setGenericName("自审校验药品");
        Long pid = callAs("alice", List.of(), () -> productService.create(body).getId());

        runAs("alice", () -> assertThatThrownBy(() -> firstAuditService.approve("product", pid, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(403);
                    assertThat(e.getMessage()).contains("不能审核本人提交的首营资料");
                }));

        runAs("bob", () -> firstAuditService.approve("product", pid, "同意"));
        assertThat(productRepository.findById(pid).orElseThrow().getFirstAuditStatus()).isEqualTo("APPROVED");
    }

    // ==================== P2-11 调拨 / 采购退货作废 ====================

    @Test
    void 调拨作废反向流水并回滚两仓库存() {
        LocalDate today = LocalDate.now();
        Long toWarehouseId = newWarehouse("作废目标仓", "ROOM_TEMP");
        String batch = "VOID-TR-" + System.nanoTime();
        createStock(batch, today.plusDays(180), new BigDecimal("10"), new BigDecimal("6"));

        StockTransferVO transfer = stockTransferService.create(new StockTransferCreateRequest(warehouseId,
                toWarehouseId, today, "待作废调拨",
                List.of(new StockTransferItemRequest(productId, batch, new BigDecimal("4")))));
        stockTransferService.submit(transfer.id());
        approveAsApprover("STOCK_TRANSFER", transfer.id());
        wmsCallbackFull("STOCK_TRANSFER", transfer.id());
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, toWarehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("4");

        StockTransferVO voided = stockTransferService.voidTransfer(transfer.id(), "测试作废调拨");
        assertThat(voided.status()).isEqualTo("VOID");

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, toWarehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("0");

        List<StockTransaction> txns = stockTransactionRepository.findByRefTypeAndRefId(REF_TRANSFER, transfer.id());
        assertThat(txns).hasSize(4);
        assertThat(txns).anyMatch(t -> "OUT".equals(t.getOpType()) && toWarehouseId.equals(t.getLocationId()));
        assertThat(txns).anyMatch(t -> "IN".equals(t.getOpType()) && warehouseId.equals(t.getLocationId()));
        assertThat(lotTraceRepository.findByProductIdAndBatchNoOrderByIdAsc(productId, batch))
                .anyMatch(t -> TRACE_TRANSFER_VOID.equals(t.getSourceType())
                        || TRACE_TRANSFER_VOID.equals(t.getTargetType()));

        assertThatThrownBy(() -> stockTransferService.voidTransfer(transfer.id(), "重复作废"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 采购退货作废回补库存并作废红字应付() {
        LocalDate today = LocalDate.now();
        String batch = "VOID-PR-" + System.nanoTime();
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("5")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        PurchaseReceiveVO receive = receiveApproved(new PurchaseReceiveCreateRequest(warehouseId,
                po.id(), today, List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                        today.minusDays(5), today.plusDays(180), new BigDecimal("5"), "INS-VOID"))));

        PurchaseReturnVO ret = purchaseReturnService.create(new PurchaseReturnCreateRequest(receive.id(),
                supplierId, today, "待作废退货",
                List.of(new PurchaseReturnItemRequest(productId, batch, new BigDecimal("3"), warehouseId))));
        purchaseReturnService.submit(ret.id());
        approveAsApprover("PURCHASE_RETURN", ret.id());
        wmsCallbackFull("PURCHASE_RETURN", ret.id());
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("7");

        PurchaseReturnVO voided = purchaseReturnService.voidReturn(ret.id(), "测试作废退货");
        assertThat(voided.status()).isEqualTo("VOID");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");

        List<ApInvoice> aps = apInvoiceRepository.findByBizTypeAndBizId(REF_PURCHASE_RETURN, ret.id());
        assertThat(aps).hasSize(1);
        assertThat(aps.get(0).getStatus()).isEqualTo("VOID");

        assertThat(lotTraceRepository.findByProductIdAndBatchNoOrderByIdAsc(productId, batch))
                .anyMatch(t -> TRACE_PURCHASE_RETURN_VOID.equals(t.getSourceType()));
    }

    private Long newWarehouse(String name, String tempType) {
        Warehouse w = new Warehouse();
        w.setName(name);
        w.setTempType(tempType);
        w.setStatus("ACTIVE");
        return warehouseRepository.save(w).getId();
    }
}
