package com.pharma.compliance;

import com.pharma.finance.repository.ArInvoiceRepository;
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
 * 批次十三（决策 1）：退货专员可作废他人销售/采购退货单。
 * 销售退货已验收单作废需回冲库存 + 作废红字应收；采购退货作废需回补库存 + 作废红字应付。
 */
class ReturnVoidPermissionTest extends AbstractComplianceTest {

    @Autowired
    private SalesReturnService salesReturnService;
    @Autowired
    private PurchaseReturnService purchaseReturnService;
    @Autowired
    private ArInvoiceRepository arInvoiceRepository;

    private static final String BATCH_S = "VOID-S-" + System.nanoTime();
    private static final String BATCH_P = "VOID-P-" + System.nanoTime();

    private static final List<String> RETURN_SPECIALIST = List.of(
            "sales:return:view", "sales:return:create", "sales:return:receive", "sales:return:void",
            "purchase:return:view", "purchase:return:create", "purchase:return:void",
            "inventory:stock:view");

    @Test
    @DisplayName("退货专员作废他人已验收销售退货：库存回冲 + 红字应收作废")
    void returnSpecialistVoidsOthersReceivedSalesReturn() {
        LocalDate today = LocalDate.now();
        SalesDeliveryVO delivery = delivered("sales-owner", BATCH_S);
        SalesReturnVO ret = callAs("sales-owner", List.of(), () -> salesReturnService.create(
                new SalesReturnCreateRequest(delivery.id(), null, today, "客户退货",
                        List.of(new SalesReturnItemRequest(productId, BATCH_S, new BigDecimal("2"), warehouseId)))));

        callAs("return-specialist", RETURN_SPECIALIST, () -> salesReturnService.submit(ret.id()));
        approveAsApprover("SALES_RETURN", ret.id());
        callAs("return-specialist", RETURN_SPECIALIST, () -> salesReturnService.receive(ret.id(),
                new SalesReturnReceiveRequest(List.of(
                        new SalesReturnReceiveItemRequest(ret.items().get(0).id(), true, null)))));
        assertThat(stockQty(BATCH_S)).isEqualByComparingTo("2");
        assertThat(arInvoiceRepository.findByBizTypeAndBizIdAndStatusNot("SALES_RETURN", ret.id(), "VOID"))
                .isNotEmpty();

        // 他人（退货专员）作废 → 允许，回补的库存收回（2 → 0），红字应收作废
        SalesReturnVO voided = callAs("return-specialist", RETURN_SPECIALIST,
                () -> salesReturnService.voidReturn(ret.id(), "客户取消退货"));
        assertThat(voided.status()).isEqualTo("VOID");
        assertThat(stockQty(BATCH_S)).isEqualByComparingTo("0");
        assertThat(arInvoiceRepository.findByBizTypeAndBizIdAndStatusNot("SALES_RETURN", ret.id(), "VOID"))
                .isEmpty();
    }

    @Test
    @DisplayName("退货专员作废他人采购退货：库存回补 + 红字应付作废")
    void returnSpecialistVoidsOthersPurchaseReturn() {
        LocalDate today = LocalDate.now();
        createStock(BATCH_P, today.plusDays(200), new BigDecimal("10"), new BigDecimal("4"));

        PurchaseReturnVO ret = callAs("buyer-owner", List.of(), () -> purchaseReturnService.create(
                new PurchaseReturnCreateRequest(null, supplierId, today, "质量退货",
                        List.of(new PurchaseReturnItemRequest(productId, BATCH_P, new BigDecimal("3"), warehouseId)))));
        callAs("buyer-owner", List.of(), () -> purchaseReturnService.submit(ret.id()));
        approveAsApprover("PURCHASE_RETURN", ret.id());
        wmsCallbackFull("PURCHASE_RETURN", ret.id());
        assertThat(stockQty(BATCH_P)).isEqualByComparingTo("7");

        PurchaseReturnVO voided = callAs("return-specialist", RETURN_SPECIALIST,
                () -> purchaseReturnService.voidReturn(ret.id(), "供应商拒收"));
        assertThat(voided.status()).isEqualTo("VOID");
        assertThat(stockQty(BATCH_P)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("草稿销售退货可直接作废（不涉及库存与应收）")
    void voidDraftSalesReturn() {
        LocalDate today = LocalDate.now();
        SalesDeliveryVO delivery = delivered("sales-owner2", BATCH_S + "-D");
        SalesReturnVO ret = callAs("sales-owner2", List.of(), () -> salesReturnService.create(
                new SalesReturnCreateRequest(delivery.id(), null, today, "草稿退货",
                        List.of(new SalesReturnItemRequest(productId, BATCH_S + "-D", new BigDecimal("1"), warehouseId)))));

        BigDecimal before = stockQty(BATCH_S + "-D");
        SalesReturnVO voided = callAs("return-specialist", RETURN_SPECIALIST,
                () -> salesReturnService.voidReturn(ret.id(), null));
        assertThat(voided.status()).isEqualTo("VOID");
        assertThat(stockQty(BATCH_S + "-D")).isEqualByComparingTo(before);
    }

    private BigDecimal stockQty(String batch) {
        return stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity();
    }

    /** 以 user 身份完成 采购入库 → 销售出库，制造可退批次（出库 10、库存余 0）。 */
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
                                today.minusDays(10), today.plusDays(180), new BigDecimal("5"), "INS-VOID")))));

        SalesOrderVO so = callAs(user, List.of(), () -> salesOrderService.create(
                new SalesOrderCreateRequest(customerId, today,
                        List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20"))))));
        callAs(user, List.of(), () -> salesOrderService.submit(so.id()));
        approveAsApprover("SALES_ORDER", so.id());
        return callAs(user, List.of(), () -> deliveryApproved(
                new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                        List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("10"))))));
    }
}
