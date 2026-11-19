package com.pharma.compliance;

import com.pharma.system.dto.LoginRequest;
import com.pharma.system.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2-5 交易 DTO Bean Validation：负数量 / 空明细 / 缺客户 等非法请求统一 400，
 * 并由 GlobalExceptionHandler 返回 data.errors[{field,message}] 供前端定位。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TransactionValidationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;

    private String token;

    @BeforeEach
    void loginAsAdmin() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");
        token = authService.login(request).getToken();
    }

    @Test
    @DisplayName("销售订单：客户为空 + 明细为空 → 400 且 errors 含 customerId/items")
    void salesOrderMissingCustomerAndItems() throws Exception {
        mockMvc.perform(post("/api/sales/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": null, "orderDate": "%s", "items": []}
                                """.formatted(java.time.LocalDate.now())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data.errors[*].field", hasItem("customerId")))
                .andExpect(jsonPath("$.data.errors[*].field", hasItem("items")));
    }

    @Test
    @DisplayName("采购订单：明细数量为负 → 400 且 errors 字段指向 quantity")
    void purchaseOrderNegativeQuantity() throws Exception {
        mockMvc.perform(post("/api/purchase/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId": 1, "orderDate": "%s",
                                 "items": [{"productId": 1, "quantity": -5, "unitPrice": 10}]}
                                """.formatted(java.time.LocalDate.now())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[*].field", hasItem(containsString("quantity"))));
    }

    @Test
    @DisplayName("销售退货：明细为空 → 400 且 errors 含 items")
    void salesReturnEmptyItems() throws Exception {
        mockMvc.perform(post("/api/sales/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deliveryId": 1, "returnDate": "%s", "items": []}
                                """.formatted(java.time.LocalDate.now())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[*].field", hasItem("items")));
    }

    @Test
    @DisplayName("财务补录应收：金额缺失 → 400 且 errors 含 amount")
    void manualArMissingAmount() throws Exception {
        mockMvc.perform(post("/api/finance/ar-invoices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": 1, "amount": null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[*].field", hasItem("amount")));
    }

    @Test
    @DisplayName("调拨：缺日期 → 400 且 errors 含 transferDate")
    void transferMissingDate() throws Exception {
        mockMvc.perform(post("/api/inventory/transfers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromWarehouseId": 1, "toWarehouseId": 2,
                                 "items": [{"productId": 1, "batchNo": "B1", "quantity": 1}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[*].field", hasItem("transferDate")));
    }
}
