package com.pharma.system;

import com.pharma.common.PageResult;
import com.pharma.inventory.AbstractInventoryTest;
import com.pharma.inventory.dto.StockVO;
import com.pharma.inventory.service.InventoryService;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-23 数据权限测试：
 * 1) 订单列表按 createdBy 过滤（ADMIN / approval:view 豁免）；
 * 2) 无 inventory:cost:view 权限时库存/入库/出库 unitCost 为 null。
 */
class DataScopeTest extends AbstractInventoryTest {

    @Autowired
    private InventoryService inventoryService;

    @Test
    @DisplayName("采购/销售订单列表：非授权用户只看自己创建的单据，approval:view 可看全部")
    void orderListScopedByCreator() {
        Long poA = as("buyer-a", List.of(), () -> {
            PurchaseOrderVO vo = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, LocalDate.now(),
                    List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("3"), new BigDecimal("5")))));
            return vo.id();
        });
        Long poB = as("buyer-b", List.of(), () -> purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, LocalDate.now(),
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("2"), new BigDecimal("6"))))).id());

        Long soA = as("sales-a", List.of(), () -> salesOrderService.create(new SalesOrderCreateRequest(customerId, LocalDate.now(),
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("1"), new BigDecimal("9"))))).id());
        Long soB = as("sales-b", List.of(), () -> salesOrderService.create(new SalesOrderCreateRequest(customerId, LocalDate.now(),
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("1"), new BigDecimal("9"))))).id());

        // 采购：buyer-a 只见 poA
        List<Long> buyerAIds = as("buyer-a", List.of(),
                () -> purchaseOrderService.list(null, null, 1, 200).getRecords().stream().map(PurchaseOrderVO::id).toList());
        assertThat(buyerAIds).contains(poA).doesNotContain(poB);

        // P1-30：列表冗余名称字段（供应商名/商品名）已下发
        PurchaseOrderVO poVo = as("buyer-a", List.of(),
                () -> purchaseOrderService.list(null, null, 1, 200).getRecords().stream()
                        .filter(v -> v.id().equals(poA)).findFirst().orElseThrow());
        assertThat(poVo.supplierName()).isNotBlank();
        assertThat(poVo.items().get(0).productName()).isNotBlank();

        // 采购：buyer-c 无任何可见单据
        PageResult<PurchaseOrderVO> buyerC = as("buyer-c", List.of(),
                () -> purchaseOrderService.list(null, null, 1, 200));
        assertThat(buyerC.getRecords()).extracting(PurchaseOrderVO::id).doesNotContain(poA, poB);

        // 销售：sales-a 只见 soA
        List<Long> salesAIds = as("sales-a", List.of(),
                () -> salesOrderService.list(null, null, 1, 200).getRecords().stream().map(SalesOrderVO::id).toList());
        assertThat(salesAIds).contains(soA).doesNotContain(soB);

        // approval:view 豁免，可见全部
        List<Long> viewerIds = as("approver-x", List.of("approval:view"),
                () -> salesOrderService.list(null, null, 1, 200).getRecords().stream().map(SalesOrderVO::id).toList());
        assertThat(viewerIds).contains(soA, soB);

        // ADMIN 角色豁免
        List<Long> adminIds = as("admin-x", List.of("ROLE_ADMIN"),
                () -> purchaseOrderService.list(null, null, 1, 200).getRecords().stream().map(PurchaseOrderVO::id).toList());
        assertThat(adminIds).contains(poA, poB);
    }

    @Test
    @DisplayName("成本脱敏：无 inventory:cost:view 时库存/入库/出库 unitCost 为 null，有权限时可见")
    void costMaskedWithoutPermission() {
        // 采购订单 -> 提交 -> 审批通过 -> 入库 10 件 @5
        Long poId = as("buyer", List.of(), () -> purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId,
                LocalDate.now(), List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("5"))))).id());
        as("buyer", List.of(), () -> purchaseOrderService.submit(poId));
        approveAsApprover("PURCHASE_ORDER", poId);

        String batch = "DS-" + System.nanoTime();
        Long receiveId = as("wh", List.of(), () -> receiveApproved(new PurchaseReceiveCreateRequest(
                warehouseId, poId, LocalDate.now(),
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                        LocalDate.now().minusMonths(1), LocalDate.now().plusYears(2), new BigDecimal("5"), "INS-DS")))).id());

        // 库存：无成本权限 -> null；有权限 -> 5
        StockVO maskedStock = as("viewer", List.of(),
                () -> inventoryService.list(productId, batch, null, null, 1, 10)).getRecords().get(0);
        assertThat(maskedStock.unitCost()).isNull();
        assertThat(maskedStock.productName()).isNotBlank();
        StockVO visibleStock = as("cost-viewer", List.of("inventory:cost:view"),
                () -> inventoryService.list(productId, batch, null, null, 1, 10)).getRecords().get(0);
        assertThat(visibleStock.unitCost()).isEqualByComparingTo("5");

        // 采购入库明细
        PurchaseReceiveVO maskedReceive = findReceive(receiveId, List.of());
        assertThat(maskedReceive.items().get(0).unitCost()).isNull();
        assertThat(maskedReceive.orderNo()).isNotBlank();
        assertThat(maskedReceive.supplierName()).isNotBlank();
        assertThat(maskedReceive.items().get(0).productName()).isNotBlank();
        PurchaseReceiveVO visibleReceive = findReceive(receiveId, List.of("inventory:cost:view"));
        assertThat(visibleReceive.items().get(0).unitCost()).isEqualByComparingTo("5");

        // 销售出库明细
        Long soId = as("seller", List.of(), () -> salesOrderService.create(new SalesOrderCreateRequest(customerId,
                LocalDate.now(), List.of(new SalesOrderItemRequest(productId, new BigDecimal("2"), new BigDecimal("12"))))).id());
        as("seller", List.of(), () -> salesOrderService.submit(soId));
        approveAsApprover("SALES_ORDER", soId);
        Long deliveryId = as("wh2", List.of(), () -> deliveryApproved(new SalesDeliveryCreateRequest(
                warehouseId, soId, LocalDate.now(),
                List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("2"))))).id());

        SalesDeliveryVO maskedDelivery = findDelivery(deliveryId, List.of());
        assertThat(maskedDelivery.items().get(0).unitCost()).isNull();
        assertThat(maskedDelivery.orderNo()).isNotBlank();
        assertThat(maskedDelivery.customerName()).isNotBlank();
        assertThat(maskedDelivery.items().get(0).productName()).isNotBlank();
        SalesDeliveryVO visibleDelivery = findDelivery(deliveryId, List.of("inventory:cost:view"));
        assertThat(visibleDelivery.items().get(0).unitCost()).isEqualByComparingTo("5");
    }

    private PurchaseReceiveVO findReceive(Long id, List<String> authorities) {
        return as("viewer-r", authorities, () -> purchaseReceiveService.list(null, 1, 200))
                .getRecords().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    private SalesDeliveryVO findDelivery(Long id, List<String> authorities) {
        return as("viewer-d", authorities, () -> salesDeliveryService.list(null, 1, 200))
                .getRecords().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    /** 以指定用户 + 权限执行并返回结果。 */
    private <T> T as(String username, List<String> authorities, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            List<GrantedAuthority> gas = authorities.stream()
                    .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", gas));
            SecurityContextHolder.setContext(context);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
