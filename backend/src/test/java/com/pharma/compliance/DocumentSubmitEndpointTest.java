package com.pharma.compliance;

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
import com.pharma.system.dto.CreateUserRequest;
import com.pharma.system.dto.LoginRequest;
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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次十四：四类单据「提交审批」端点（含批量提交与状态筛选）的 HTTP 契约与权限校验。
 * 审批执行语义由 {@link DocumentApprovalFlowTest} 覆盖（服务层）。
 */
@AutoConfigureMockMvc
class DocumentSubmitEndpointTest extends AbstractComplianceTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private PurchaseReturnService purchaseReturnService;
    @Autowired
    private StockTransferService stockTransferService;

    private String adminToken;

    private static final String BATCH = "SUB-" + System.nanoTime();

    @BeforeEach
    void loginAsAdmin() {
        adminToken = login("admin", "admin123");
    }

    @Test
    @DisplayName("采购入库：submit 端点置 SUBMITTED、状态筛选可见、批量提交逐项结果、无权限 403")
    void purchaseReceiveSubmitEndpoint() throws Exception {
        LocalDate today = LocalDate.now();
        PurchaseOrderVO po = approvedOrder(new BigDecimal("10"));
        String batch = BATCH + "-RC";

        PurchaseReceiveVO receive = callAs("buyer", List.of(), () -> purchaseReceiveService.create(
                new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                        List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                                today.minusDays(5), today.plusDays(180), new BigDecimal("5"), "INS-SUB")))));

        mockMvc.perform(post("/api/purchase/receives/" + receive.id() + "/submit")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        mockMvc.perform(get("/api/purchase/receives")
                        .param("status", "SUBMITTED")
                        .param("size", "200")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].id", hasItem(receive.id().intValue())));

        // 批量提交：一条已提交（失败）+ 一条不存在（失败）
        mockMvc.perform(post("/api/purchase/receives/batch-submit")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + receive.id() + ",99999999]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(2));

        // SALES 角色无 purchase:receive:submit
        String username = "no-submit-" + System.nanoTime();
        callAs("boss", List.of("ROLE_ADMIN"), () -> userService.create(
                new CreateUserRequest(username, "pwd12345", "无提交权限", null, List.of("SALES"))));
        String token = login(username, "pwd12345");
        mockMvc.perform(post("/api/purchase/receives/" + receive.id() + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("销售出库 / 采购退货 / 仓库调拨：submit 端点均返回 SUBMITTED")
    void otherDocumentSubmitEndpoints() throws Exception {
        LocalDate today = LocalDate.now();

        // 销售出库（草稿明细不锁批，提交不校验库存）
        SalesOrderVO so = approvedSalesOrder(new BigDecimal("3"));
        SalesDeliveryVO delivery = callAs("seller", List.of(), () -> salesDeliveryService.create(
                new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                        List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("3"))))));
        mockMvc.perform(post("/api/sales/deliveries/" + delivery.id() + "/submit")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        // 采购退货（需真实批次库存，建单只校验不扣减）
        String batch = BATCH + "-PR";
        createStock(batch, today.plusDays(180), new BigDecimal("10"), new BigDecimal("5"));
        PurchaseReturnVO purchaseReturn = callAs("buyer", List.of(), () -> purchaseReturnService.create(
                new PurchaseReturnCreateRequest(null, supplierId, today, "提交端点测试",
                        List.of(new PurchaseReturnItemRequest(productId, batch, new BigDecimal("2"), warehouseId)))));
        mockMvc.perform(post("/api/purchase/returns/" + purchaseReturn.id() + "/submit")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        // 仓库调拨
        String transferBatch = BATCH + "-TR";
        createStock(transferBatch, today.plusDays(180), new BigDecimal("10"), new BigDecimal("5"));
        Long toWarehouseId = newWarehouse();
        StockTransferVO transfer = callAs("keeper", List.of(), () -> stockTransferService.create(
                new StockTransferCreateRequest(warehouseId, toWarehouseId, today, "提交端点测试",
                        List.of(new StockTransferItemRequest(productId, transferBatch, new BigDecimal("1"))))));
        mockMvc.perform(post("/api/inventory/transfers/" + transfer.id() + "/submit")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));
    }

    // ==================== 辅助 ====================

    private PurchaseOrderVO approvedOrder(BigDecimal qty) {
        LocalDate today = LocalDate.now();
        PurchaseOrderVO po = callAs("buyer", List.of(), () -> purchaseOrderService.create(
                new PurchaseOrderCreateRequest(supplierId, today,
                        List.of(new PurchaseOrderItemRequest(productId, qty, new BigDecimal("5"))))));
        callAs("buyer", List.of(), () -> purchaseOrderService.submit(po.id()));
        approveAsApprover("PURCHASE_ORDER", po.id());
        return po;
    }

    private SalesOrderVO approvedSalesOrder(BigDecimal qty) {
        LocalDate today = LocalDate.now();
        SalesOrderVO so = callAs("seller", List.of(), () -> salesOrderService.create(
                new SalesOrderCreateRequest(customerId, today,
                        List.of(new SalesOrderItemRequest(productId, qty, new BigDecimal("20"))))));
        callAs("seller", List.of(), () -> salesOrderService.submit(so.id()));
        approveAsApprover("SALES_ORDER", so.id());
        return so;
    }

    private Long newWarehouse() {
        com.pharma.base.entity.Warehouse w = new com.pharma.base.entity.Warehouse();
        w.setName("提交端点测试仓-" + System.nanoTime());
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
}
