package com.pharma.compliance;

import com.pharma.common.BusinessException;
import com.pharma.common.batch.BatchItemResult;
import com.pharma.common.batch.BatchOperationRunner;
import com.pharma.common.batch.BatchResult;
import com.pharma.base.entity.Warehouse;
import com.pharma.inventory.dto.StockTransferCreateRequest;
import com.pharma.inventory.dto.StockTransferItemRequest;
import com.pharma.inventory.dto.StockTransferVO;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
import com.pharma.purchase.dto.PurchaseReceiveVO;
import com.pharma.purchase.dto.PurchaseReturnCreateRequest;
import com.pharma.purchase.dto.PurchaseReturnItemRequest;
import com.pharma.purchase.dto.PurchaseReturnVO;
import com.pharma.purchase.dto.PurchaseReturnCreateRequest;
import com.pharma.purchase.dto.PurchaseReturnItemRequest;
import com.pharma.sales.dto.SalesDeliveryCreateRequest;
import com.pharma.sales.dto.SalesDeliveryItemRequest;
import com.pharma.sales.dto.SalesDeliveryVO;
import com.pharma.sales.dto.SalesOrderCreateRequest;
import com.pharma.sales.dto.SalesOrderItemRequest;
import com.pharma.sales.dto.SalesOrderVO;
import com.pharma.sales.dto.SalesReturnCreateRequest;
import com.pharma.sales.dto.SalesReturnItemRequest;
import com.pharma.sales.dto.SalesReturnVO;
import com.pharma.system.dto.CreateUserRequest;
import com.pharma.system.dto.LoginRequest;
import com.pharma.system.entity.AuditLog;
import com.pharma.system.repository.AuditLogRepository;
import com.pharma.system.service.AuthService;
import com.pharma.system.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次十四：单据导出 / 入库出库详情 / 批量作废（BatchResult 部分成功语义）。
 * 导出与详情走 MockMvc（真实过滤器 + 鉴权），批量执行器语义走服务层直调。
 */
@AutoConfigureMockMvc
class DocumentOpsTest extends AbstractComplianceTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private BatchOperationRunner batchRunner;
    @Autowired
    private com.pharma.purchase.service.PurchaseReturnService purchaseReturnService;
    @Autowired
    private com.pharma.sales.service.SalesReturnService salesReturnService;
    @Autowired
    private com.pharma.inventory.service.StockTransferService stockTransferService;

    private String adminToken;

    @BeforeEach
    void loginAsAdmin() {
        adminToken = login("admin", "admin123");
    }

    // ==================== 任务 1：导出 ====================

    @Test
    @DisplayName("采购订单导出：xlsx 响应 + 命中行数头 + EXPORT 审计含查询条件")
    void exportPurchaseOrders() throws Exception {
        PurchaseOrderVO po = newApprovedPurchaseOrder(new BigDecimal("5"), new BigDecimal("12.50"));

        byte[] body = mockMvc.perform(get("/api/purchase/orders/export")
                        .param("keyword", po.orderNo())
                        .param("status", "APPROVED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString(XLSX)))
                .andExpect(header().string("X-Export-Rows", "1"))
                .andExpect(header().string("X-Export-Truncated", "false"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).hasSizeGreaterThan(1000);
        assertThat(new String(body, 0, 2)).isEqualTo("PK"); // xlsx 为 zip 容器

        AuditLog log = exportAudit("PURCHASE_ORDER");
        assertThat(log.getDetail()).contains("keyword=" + po.orderNo()).contains("status=APPROVED").contains("共 1 行");
        assertThat(log.getAfterValue()).contains("\"rows\":1").contains("\"truncated\":false");
    }

    @Test
    @DisplayName("采购入库导出：按 keyword 精确命中 1 行并写 EXPORT 审计")
    void exportPurchaseReceives() throws Exception {
        String batch = "EXP-RC-" + System.nanoTime();
        PurchaseReceiveVO receive = newReceive(newApprovedPurchaseOrder(new BigDecimal("6"), new BigDecimal("9")).id(),
                batch, new BigDecimal("6"), new BigDecimal("9"));

        mockMvc.perform(get("/api/purchase/receives/export")
                        .param("keyword", receive.receiveNo())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString(XLSX)))
                .andExpect(header().string("X-Export-Rows", "1"));

        assertThat(exportAudit("PURCHASE_RECEIVE").getDetail()).contains("共 1 行");
    }

    @Test
    @DisplayName("销售订单/出库/退货导出：均返回 xlsx 并可带查询条件")
    void exportSalesDocuments() throws Exception {
        String batch = "EXP-DO-" + System.nanoTime();
        SalesDeliveryVO delivery = newDelivery(batch, new BigDecimal("8"), new BigDecimal("10"),
                new BigDecimal("4"), new BigDecimal("20"));

        mockMvc.perform(get("/api/sales/deliveries/export")
                        .param("keyword", delivery.deliveryNo())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString(XLSX)))
                .andExpect(header().string("X-Export-Rows", "1"));

        SalesReturnVO salesReturn = callAs("seller", List.of(), () -> salesReturnService.create(
                new SalesReturnCreateRequest(delivery.id(), null, LocalDate.now(), "导出测试",
                        List.of(new SalesReturnItemRequest(productId, batch, new BigDecimal("1"), warehouseId)))));
        mockMvc.perform(get("/api/sales/returns/export")
                        .param("keyword", salesReturn.returnNo())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString(XLSX)))
                .andExpect(header().string("X-Export-Rows", "1"));

        mockMvc.perform(get("/api/sales/orders/export")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString(XLSX)));

        assertThat(exportAudit("SALES_DELIVERY").getDetail()).contains("共 1 行");
        assertThat(exportAudit("SALES_RETURN").getDetail()).contains("共 1 行");
    }

    @Test
    @DisplayName("采购退货导出：返回 xlsx 且写 EXPORT 审计")
    void exportPurchaseReturns() throws Exception {
        String batch = "EXP-PR-" + System.nanoTime();
        PurchaseReceiveVO receive = newReceive(newApprovedPurchaseOrder(new BigDecimal("5"), new BigDecimal("7")).id(),
                batch, new BigDecimal("5"), new BigDecimal("7"));

        String returnNo = callAs("buyer", List.of(), () -> purchaseReturnService.create(
                new PurchaseReturnCreateRequest(receive.id(), supplierId, LocalDate.now(), "导出测试",
                        List.of(new PurchaseReturnItemRequest(productId, batch, new BigDecimal("2"), warehouseId))))
                .returnNo());

        mockMvc.perform(get("/api/purchase/returns/export")
                        .param("keyword", returnNo)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString(XLSX)))
                .andExpect(header().string("X-Export-Rows", "1"));

        assertThat(exportAudit("PURCHASE_RETURN").getDetail()).contains("共 1 行");
    }

    // ==================== 任务 2：入库/出库详情 ====================

    @Test
    @DisplayName("采购入库详情：200 含明细/供应商名/订单号；不存在 404")
    void purchaseReceiveDetail() throws Exception {
        String batch = "DET-RC-" + System.nanoTime();
        PurchaseOrderVO po = newApprovedPurchaseOrder(new BigDecimal("4"), new BigDecimal("6"));
        PurchaseReceiveVO receive = newReceive(po.id(), batch, new BigDecimal("4"), new BigDecimal("6"));

        mockMvc.perform(get("/api/purchase/receives/" + receive.id())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiveNo").value(receive.receiveNo()))
                .andExpect(jsonPath("$.data.orderNo").value(po.orderNo()))
                .andExpect(jsonPath("$.data.supplierName").value("测试供应商"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].batchNo").value(batch));

        mockMvc.perform(get("/api/purchase/receives/99999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("销售出库详情：200 含明细/客户名/订单号；不存在 404")
    void salesDeliveryDetail() throws Exception {
        String batch = "DET-DO-" + System.nanoTime();
        SalesDeliveryVO delivery = newDelivery(batch, new BigDecimal("6"), new BigDecimal("10"),
                new BigDecimal("3"), new BigDecimal("20"));

        mockMvc.perform(get("/api/sales/deliveries/" + delivery.id())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryNo").value(delivery.deliveryNo()))
                .andExpect(jsonPath("$.data.customerName").value("测试客户"))
                .andExpect(jsonPath("$.data.orderNo").isNotEmpty())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].batchNo").value(batch));

        mockMvc.perform(get("/api/sales/deliveries/99999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("退货详情：采购/销售退货 GET /{id} 200 含明细，不存在 404")
    void returnDetails() throws Exception {
        String batch = "DET-RT-" + System.nanoTime();
        PurchaseReceiveVO receive = newReceive(newApprovedPurchaseOrder(new BigDecimal("6"), new BigDecimal("5")).id(),
                batch, new BigDecimal("6"), new BigDecimal("5"));
        PurchaseReturnVO purchaseReturn = callAs("buyer", List.of(), () -> purchaseReturnService.create(
                new PurchaseReturnCreateRequest(receive.id(), supplierId, LocalDate.now(), "详情测试",
                        List.of(new PurchaseReturnItemRequest(productId, batch, new BigDecimal("2"), warehouseId)))));
        mockMvc.perform(get("/api/purchase/returns/" + purchaseReturn.id())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.returnNo").value(purchaseReturn.returnNo()))
                .andExpect(jsonPath("$.data.supplierName").value("测试供应商"))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        SalesDeliveryVO delivery = newDelivery("DET-SR-" + System.nanoTime(), new BigDecimal("6"),
                new BigDecimal("8"), new BigDecimal("3"), new BigDecimal("18"));
        SalesReturnVO salesReturn = callAs("seller", List.of(), () -> salesReturnService.create(
                new SalesReturnCreateRequest(delivery.id(), null, LocalDate.now(), "详情测试",
                        List.of(new SalesReturnItemRequest(productId, delivery.items().get(0).batchNo(),
                                new BigDecimal("1"), warehouseId)))));
        mockMvc.perform(get("/api/sales/returns/" + salesReturn.id())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.returnNo").value(salesReturn.returnNo()))
                .andExpect(jsonPath("$.data.customerName").value("测试客户"))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        mockMvc.perform(get("/api/purchase/returns/99999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sales/returns/99999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("订单批量作废：不存在单据逐项失败；空 ids 400；无权限 403")
    void orderBatchVoid() throws Exception {
        mockMvc.perform(post("/api/purchase/orders/batch-void")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[99999999],\"reason\":\"不存在\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.results[0].message", containsString("不存在")));

        mockMvc.perform(post("/api/sales/orders/batch-void")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[],\"reason\":\"空\"}"))
                .andExpect(status().isBadRequest());

        // SALES 角色自带 sales:order:void，故用采购订单批量作废验证无权限拦截
        String username = "no-order-void-" + System.nanoTime();
        callAs("boss", List.of("ROLE_ADMIN"), () -> userService.create(
                new CreateUserRequest(username, "pwd12345", "无订单作废权限", null, List.of("SALES"))));
        String token = login(username, "pwd12345");
        mockMvc.perform(post("/api/purchase/orders/batch-void")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"reason\":\"无权\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("仓库调拨：详情 200/404、导出 xlsx、批量作废逐项失败")
    void stockTransferOps() throws Exception {
        Long toWarehouseId = newWarehouse();
        String batch = "DET-TR-" + System.nanoTime();
        newReceive(newApprovedPurchaseOrder(new BigDecimal("5"), new BigDecimal("4")).id(),
                batch, new BigDecimal("5"), new BigDecimal("4"));
        StockTransferVO transfer = callAs("keeper", List.of(), () -> stockTransferService.create(
                new StockTransferCreateRequest(warehouseId, toWarehouseId, LocalDate.now(), "调拨测试",
                        List.of(new StockTransferItemRequest(productId, batch, new BigDecimal("2"))))));

        mockMvc.perform(get("/api/inventory/transfers/" + transfer.id())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transferNo").value(transfer.transferNo()))
                .andExpect(jsonPath("$.data.fromWarehouseName").value("测试仓"))
                .andExpect(jsonPath("$.data.toWarehouseName", containsString("测试仓B")))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].batchNo").value(batch));

        mockMvc.perform(get("/api/inventory/transfers/99999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/inventory/transfers/export")
                        .param("keyword", transfer.transferNo())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString(XLSX)))
                .andExpect(header().string("X-Export-Rows", "1"));

        mockMvc.perform(post("/api/inventory/transfers/batch-void")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[99999999],\"reason\":\"不存在\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.results[0].message", containsString("不存在")));
    }

    // ==================== 任务 3：批量 ====================
    @Test
    @DisplayName("批量作废：空 ids → 400；无权限 → 403")
    void batchVoidGuards() throws Exception {
        mockMvc.perform(post("/api/purchase/receives/batch-void")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[],\"reason\":\"空\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        String username = "no-void-" + System.nanoTime();
        callAs("boss", List.of("ROLE_ADMIN"), () -> userService.create(
                new CreateUserRequest(username, "pwd12345", "无作废权限", null, List.of("SALES"))));
        String token = login(username, "pwd12345");

        mockMvc.perform(post("/api/purchase/receives/batch-void")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"reason\":\"无权\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("批量作废：单条不存在时逐项失败，返回 BatchResult 结构（total/successCount/failedCount/results）")
    void batchVoidPerItemFailure() throws Exception {
        mockMvc.perform(post("/api/sales/deliveries/batch-void")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[99999999],\"reason\":\"不存在\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.results[0].index").value(0))
                .andExpect(jsonPath("$.data.results[0].success").value(false))
                .andExpect(jsonPath("$.data.results[0].message", containsString("不存在")));
    }

    @Test
    @DisplayName("批量作废执行器：全部成功 / 部分成功（一条状态不对）/ 权限不足逐项失败")
    void batchVoidRunnerSemantics() {
        BatchResult allOk = callAs("admin", List.of("purchase:receive:void"), () ->
                batchRunner.runVoid("purchase_receive", "purchase:receive:void",
                        List.of(11L, 12L), "批量", id -> id));
        assertThat(allOk.getTotal()).isEqualTo(2);
        assertThat(allOk.getSuccessCount()).isEqualTo(2);
        assertThat(allOk.getFailedCount()).isZero();

        BatchResult partial = callAs("admin", List.of("purchase:receive:void"), () ->
                batchRunner.runVoid("purchase_receive", "purchase:receive:void",
                        List.of(11L, 12L, 13L), "批量", id -> {
                            if (id == 12L) {
                                throw BusinessException.badRequest("入库单已作废: RC-TEST");
                            }
                            return id;
                        }));
        assertThat(partial.getTotal()).isEqualTo(3);
        assertThat(partial.getSuccessCount()).isEqualTo(2);
        assertThat(partial.getFailedCount()).isEqualTo(1);
        assertThat(partial.getResults()).extracting(BatchItemResult::getIndex).containsExactly(0, 1, 2);
        assertThat(partial.getResults().get(1).isSuccess()).isFalse();
        assertThat(partial.getResults().get(1).getMessage()).contains("已作废");
        assertThat(partial.getResults().get(2).isSuccess()).isTrue();

        BatchResult denied = callAs("nobody", List.of(), () ->
                batchRunner.runVoid("purchase_receive", "purchase:receive:void",
                        List.of(11L), null, id -> id));
        assertThat(denied.getFailedCount()).isEqualTo(1);
        assertThat(denied.getResults().get(0).getMessage()).contains("无权限");

        assertThatThrownBy(() -> callAs("admin", List.of("purchase:receive:void"), () ->
                batchRunner.runVoid("purchase_receive", "purchase:receive:void", List.of(), null, id -> id)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ids");
    }

    // ==================== 辅助 ====================

    private Long newWarehouse() {
        Warehouse w = new Warehouse();
        w.setName("测试仓B-" + System.nanoTime());
        w.setTempType("ROOM_TEMP");
        w.setStatus("ACTIVE");
        return warehouseRepository.save(w).getId();
    }

    private String login(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return authService.login(request).getToken();
    }

    private AuditLog exportAudit(String objectType) {
        // 取最新一条：库里可能存在历史（冒烟脚本已提交）的 EXPORT 审计
        return auditLogRepository.findAll().stream()
                .filter(a -> "EXPORT".equals(a.getAction()) && objectType.equals(a.getObjectType())
                        && "admin".equals(a.getUsername()))
                .max(java.util.Comparator.comparing(AuditLog::getId))
                .orElseThrow(() -> new AssertionError("应写 EXPORT 审计: " + objectType));
    }

    private PurchaseOrderVO newApprovedPurchaseOrder(BigDecimal quantity, BigDecimal unitPrice) {
        PurchaseOrderVO po = callAs("buyer", List.of(), () -> purchaseOrderService.create(
                new PurchaseOrderCreateRequest(supplierId, LocalDate.now(),
                        List.of(new PurchaseOrderItemRequest(productId, quantity, unitPrice)))));
        callAs("buyer", List.of(), () -> purchaseOrderService.submit(po.id()));
        approveAsApprover("PURCHASE_ORDER", po.id());
        return po;
    }

    /** 建单 + 提交 + 审批通过（批次十四起入库需审批后才落库存）。 */
    private PurchaseReceiveVO newReceive(Long orderId, String batch, BigDecimal quantity, BigDecimal unitCost) {
        LocalDate today = LocalDate.now();
        return callAs("buyer", List.of(), () -> receiveApproved(
                new PurchaseReceiveCreateRequest(warehouseId, orderId, today,
                        List.of(new PurchaseReceiveItemRequest(productId, quantity, batch,
                                today.minusDays(5), today.plusDays(180), unitCost, "INS-" + batch)))));
    }

    private SalesDeliveryVO newDelivery(String batch, BigDecimal buyQty, BigDecimal buyPrice,
                                        BigDecimal sellQty, BigDecimal sellPrice) {
        PurchaseOrderVO po = newApprovedPurchaseOrder(buyQty, buyPrice);
        newReceive(po.id(), batch, buyQty, buyPrice);

        SalesOrderVO so = callAs("seller", List.of(), () -> salesOrderService.create(
                new SalesOrderCreateRequest(customerId, LocalDate.now(),
                        List.of(new SalesOrderItemRequest(productId, sellQty, sellPrice)))));
        callAs("seller", List.of(), () -> salesOrderService.submit(so.id()));
        approveAsApprover("SALES_ORDER", so.id());
        return callAs("seller", List.of(), () -> deliveryApproved(
                new SalesDeliveryCreateRequest(warehouseId, so.id(), LocalDate.now(),
                        List.of(new SalesDeliveryItemRequest(productId, sellQty)))));
    }
}
