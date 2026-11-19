package com.pharma.sales;

import com.pharma.finance.entity.ArInvoice;
import com.pharma.finance.repository.ArInvoiceRepository;
import com.pharma.inventory.AbstractInventoryTest;
import com.pharma.inventory.entity.StockTransaction;
import com.pharma.inventory.repository.StockTransactionRepository;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
import com.pharma.purchase.dto.PurchaseReceiveVO;
import com.pharma.quality.repository.UnqualifiedRepository;
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

import static com.pharma.inventory.service.InventoryLedgerService.REF_SALES_RETURN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 销售退货（P1-11）：审批流 + 验收分流（合格回补库存/回冲成本，不合格登记隔离）+ 红字应收。
 */
class SalesReturnTest extends AbstractInventoryTest {

    @Autowired
    private SalesReturnService salesReturnService;
    @Autowired
    private StockTransactionRepository stockTransactionRepository;
    @Autowired
    private ArInvoiceRepository arInvoiceRepository;
    @Autowired
    private UnqualifiedRepository unqualifiedRepository;

    private static final String BATCH = "SR-" + System.nanoTime();

    @Test
    void 合格退货回补库存并回冲成本() {
        LocalDate today = LocalDate.now();
        SalesDeliveryVO delivery = delivered(today, new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("5"));

        SalesReturnVO ret = salesReturnService.create(new SalesReturnCreateRequest(delivery.id(), null, today,
                "客户退货", List.of(new SalesReturnItemRequest(productId, BATCH, new BigDecimal("4"), warehouseId))));
        assertThat(ret.status()).isEqualTo("DRAFT");
        assertThat(ret.totalAmount()).isEqualByComparingTo("80");
        assertThat(ret.items()).hasSize(1);
        assertThat(ret.items().get(0).unitCost()).isEqualByComparingTo("5");

        salesReturnService.submit(ret.id());
        approveAsApprover("SALES_RETURN", ret.id());
        SalesReturnVO received = salesReturnService.receive(ret.id(),
                new SalesReturnReceiveRequest(List.of(
                        new SalesReturnReceiveItemRequest(ret.items().get(0).id(), true, null))));

        assertThat(received.status()).isEqualTo("RECEIVED");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("4");

        List<StockTransaction> txns = stockTransactionRepository.findByRefTypeAndRefId(REF_SALES_RETURN, ret.id());
        assertThat(txns).anyMatch(t -> "IN".equals(t.getOpType()) && t.getQuantity().compareTo(BigDecimal.ZERO) > 0);

        List<ArInvoice> reds = arInvoiceRepository.findByBizTypeAndBizId("SALES_RETURN", ret.id());
        assertThat(reds).hasSize(1);
        assertThat(reds.get(0).getInvoiceAmount()).isEqualByComparingTo("-80");
    }

    @Test
    void 不合格退货登记隔离且不回补库存() {
        LocalDate today = LocalDate.now();
        SalesDeliveryVO delivery = delivered(today, new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("5"));
        BigDecimal stockBefore = stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity();

        SalesReturnVO ret = salesReturnService.create(new SalesReturnCreateRequest(delivery.id(), 88L, today,
                "门店退货", List.of(new SalesReturnItemRequest(productId, BATCH, new BigDecimal("2"), warehouseId))));
        salesReturnService.submit(ret.id());
        approveAsApprover("SALES_RETURN", ret.id());

        long before = unqualifiedRepository.count();
        SalesReturnVO received = salesReturnService.receive(ret.id(),
                new SalesReturnReceiveRequest(List.of(
                        new SalesReturnReceiveItemRequest(ret.items().get(0).id(), false, "包装破损"))));

        assertThat(received.status()).isEqualTo("RECEIVED");
        assertThat(received.items().get(0).acceptResult()).isEqualTo("UNQUALIFIED");
        assertThat(unqualifiedRepository.count()).isEqualTo(before + 1);
        // 库存未被回补
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo(stockBefore);
    }

    /** 采购入库 10 @5 → 销售出库 10，制造可退批次。 */
    private SalesDeliveryVO delivered(LocalDate today, BigDecimal qty, BigDecimal price, BigDecimal cost) {
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, qty, cost))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        PurchaseReceiveVO receive = receiveApproved(new PurchaseReceiveCreateRequest(warehouseId,
                po.id(), today, List.of(new PurchaseReceiveItemRequest(productId, qty, BATCH,
                today.minusDays(10), today.plusDays(180), cost, "INS-SR"))));
        assertThat(receive.status()).isEqualTo("COMPLETED");

        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, qty, price))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        return deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                List.of(new SalesDeliveryItemRequest(productId, qty))));
    }
}
