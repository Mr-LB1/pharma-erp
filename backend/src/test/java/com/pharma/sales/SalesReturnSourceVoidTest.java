package com.pharma.sales;

import com.pharma.common.BusinessException;
import com.pharma.finance.repository.ArInvoiceRepository;
import com.pharma.inventory.AbstractInventoryTest;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
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
import com.pharma.sales.entity.SalesDelivery;
import com.pharma.sales.repository.SalesDeliveryRepository;
import com.pharma.sales.repository.SalesReturnRepository;
import com.pharma.sales.service.SalesReturnService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-2 / P1-B 销售出库与销售退货互斥回归：
 * - 验收侧：源销售出库单作废后，退货验收必须 400（不得回补库存、不得生成红字应收、状态不推进）；
 * - 作废侧：存在未作废销售退货单时作废出库必须 400，退货作废后才允许正常作废出库（P1-B）。
 * 覆盖链路：建退货（create）先锁源出库单头复核 ACTIVE/COMPLETED；验收（receive）先锁退货单头，
 * 再锁源出库单头复核 ACTIVE/COMPLETED；作废（voidDelivery）先锁出库单头，再拦截未作废退货单。
 */
class SalesReturnSourceVoidTest extends AbstractInventoryTest {

    @Autowired
    private SalesReturnService salesReturnService;
    @Autowired
    private ArInvoiceRepository arInvoiceRepository;
    @Autowired
    private SalesDeliveryRepository salesDeliveryRepository;
    @Autowired
    private SalesReturnRepository salesReturnRepository;

    private static final String BATCH = "SRV-" + System.nanoTime();

    @Test
    void 源出库单作废后退货验收被拒绝400且库存应收不变() {
        LocalDate today = LocalDate.now();
        // 采购入库 10 → 销售订单 → 出库审批并回传（COMPLETED），制造可退批次
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("5")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), BATCH,
                        today.minusDays(5), today.plusDays(180), new BigDecimal("5"), "INS-P02"))));

        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20")))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        SalesDeliveryVO delivery = deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("10")))));
        assertThat(delivery.status()).isEqualTo("COMPLETED");

        // 退货单提审并审批通过（APPROVED），等待验收
        SalesReturnVO ret = salesReturnService.create(new SalesReturnCreateRequest(delivery.id(), null, today,
                "客户退货", List.of(new SalesReturnItemRequest(productId, BATCH, new BigDecimal("3"), warehouseId))));
        salesReturnService.submit(ret.id());
        approveAsApprover("SALES_RETURN", ret.id());
        assertThat(salesReturnService.get(ret.id()).status()).isEqualTo("APPROVED");

        // P1-B 后正常路径已无法造出「出库单 VOID + 退货单 APPROVED」，
        // 这里直接置单据头状态模拟历史脏数据（老版本已作废出库但退货单未联动），
        // 用于验证验收侧对源单状态的兜底复核。
        SalesDelivery inconsistent = salesDeliveryRepository.findById(delivery.id()).orElseThrow();
        inconsistent.setStatus("VOID");
        salesDeliveryRepository.save(inconsistent);
        assertThat(salesDeliveryRepository.findById(delivery.id()).orElseThrow().getStatus()).isEqualTo("VOID");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("0");

        // 验收被拒 400（源单已作废），且验收未产生任何副作用
        assertThatThrownBy(() -> salesReturnService.receive(ret.id(), new SalesReturnReceiveRequest(
                List.of(new SalesReturnReceiveItemRequest(ret.items().get(0).id(), true, null)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("已作废或未完成的出库单不可退货");
                });

        assertThat(salesReturnService.get(ret.id()).status()).isEqualTo("APPROVED");
        // 验收被拒未回补库存：出库时已扣到 0，失败后仍为 0（若回补会变成 3）
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("0");
        assertThat(arInvoiceRepository.findByBizTypeAndBizId("SALES_RETURN", ret.id())).isEmpty();
    }

    /**
     * 并发重复验收的单线程回归：验收成功后再次验收必须 400（APPROVED 状态守卫），
     * 且不得重复回补库存、不得生成第二张红字应收。
     */
    @Test
    void 重复验收被拒绝400且不重复回补库存与红字应收() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-DUP";
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("5")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                        today.minusDays(5), today.plusDays(180), new BigDecimal("5"), "INS-P02D"))));

        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20")))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        SalesDeliveryVO delivery = deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("10")))));

        SalesReturnVO ret = salesReturnService.create(new SalesReturnCreateRequest(delivery.id(), null, today,
                "客户退货", List.of(new SalesReturnItemRequest(productId, batch, new BigDecimal("3"), warehouseId))));
        salesReturnService.submit(ret.id());
        approveAsApprover("SALES_RETURN", ret.id());
        salesReturnService.receive(ret.id(), new SalesReturnReceiveRequest(
                List.of(new SalesReturnReceiveItemRequest(ret.items().get(0).id(), true, null))));

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("3");
        assertThat(arInvoiceRepository.findByBizTypeAndBizId("SALES_RETURN", ret.id())).hasSize(1);

        assertThatThrownBy(() -> salesReturnService.receive(ret.id(), new SalesReturnReceiveRequest(
                List.of(new SalesReturnReceiveItemRequest(ret.items().get(0).id(), true, null)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("仅审批通过的退货单可验收");
                });

        // 副作用不重复：库存仍为 3，红字应收仍只有 1 张
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("3");
        assertThat(arInvoiceRepository.findByBizTypeAndBizId("SALES_RETURN", ret.id())).hasSize(1);
    }

    /**
     * P1-B 作废侧拦截：退货已验收（库存已回补、红字应收已生成）时，作废出库必须 400，
     * 且出库单状态/库存/红字应收均不被改动（不得重复回补、重复冲销）。
     */
    @Test
    void 存在未作废退货单时作废出库被拒绝400() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-HOLD";
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("5")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                        today.minusDays(5), today.plusDays(180), new BigDecimal("5"), "INS-P1B"))));

        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20")))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        SalesDeliveryVO delivery = deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("10")))));

        SalesReturnVO ret = salesReturnService.create(new SalesReturnCreateRequest(delivery.id(), null, today,
                "客户退货", List.of(new SalesReturnItemRequest(productId, batch, new BigDecimal("3"), warehouseId))));
        salesReturnService.submit(ret.id());
        approveAsApprover("SALES_RETURN", ret.id());
        salesReturnService.receive(ret.id(), new SalesReturnReceiveRequest(
                List.of(new SalesReturnReceiveItemRequest(ret.items().get(0).id(), true, null))));
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("3");

        // 存在 RECEIVED（未作废）退货单 → 作废出库 400
        assertThatThrownBy(() -> salesDeliveryService.voidDelivery(delivery.id(), "P1-B 作废出库"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("存在未作废的销售退货单，不能作废出库单");
                });

        // 出库单未被置 VOID，库存与红字应收均未重复变动
        assertThat(salesDeliveryService.get(delivery.id()).status()).isEqualTo("COMPLETED");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("3");
        assertThat(arInvoiceRepository.findByBizTypeAndBizId("SALES_RETURN", ret.id())).hasSize(1);
    }

    /**
     * P1-B 放行路径：退货单先作废（回冲出库库存）后，出库单作废不再被拦截，可正常回补库存到原始入库量。
     */
    @Test
    void 退货作废后可正常作废出库并回补库存() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-RELEASE";
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("5")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                        today.minusDays(5), today.plusDays(180), new BigDecimal("5"), "INS-P1R"))));

        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20")))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        SalesDeliveryVO delivery = deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("10")))));

        SalesReturnVO ret = salesReturnService.create(new SalesReturnCreateRequest(delivery.id(), null, today,
                "客户退货", List.of(new SalesReturnItemRequest(productId, batch, new BigDecimal("3"), warehouseId))));
        salesReturnService.submit(ret.id());
        approveAsApprover("SALES_RETURN", ret.id());
        salesReturnService.receive(ret.id(), new SalesReturnReceiveRequest(
                List.of(new SalesReturnReceiveItemRequest(ret.items().get(0).id(), true, null))));
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("3");

        // 先作废退货单：RECEIVED → VOID，回冲已回补的 3 件库存
        assertThat(salesReturnService.voidReturn(ret.id(), "P1-B 先作废退货").status()).isEqualTo("VOID");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("0");

        // 退货单已 VOID → 出库单作废放行，库存回补到 10
        SalesDeliveryVO voided = salesDeliveryService.voidDelivery(delivery.id(), "P1-B 退货作废后作废出库");
        assertThat(voided.status()).isEqualTo("VOID");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
    }

    /**
     * P3 建退货侧复核：源出库单已作废后建退货必须 400（create 锁源单头后复核 ACTIVE/COMPLETED），
     * 且不得留下滞留退货单、不得回补库存。
     */
    @Test
    void 源出库单作废后建退货被拒绝400() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-CREATE";
        createStock(batch, today.plusDays(180), new BigDecimal("10"), new BigDecimal("5"));

        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("5"), new BigDecimal("20")))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        SalesDeliveryVO delivery = deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("5")))));
        assertThat(delivery.status()).isEqualTo("COMPLETED");

        // 无退货单，源出库单可正常作废（P1-B 拦截不触发），库存回补到 10
        salesDeliveryService.voidDelivery(delivery.id(), "P3 建退货前作废源单");
        assertThat(salesDeliveryService.get(delivery.id()).status()).isEqualTo("VOID");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");

        // 源单已 VOID → 建退货 400，且不落滞留退货单、不动库存
        assertThatThrownBy(() -> salesReturnService.create(new SalesReturnCreateRequest(delivery.id(), null, today,
                "作废后建退货", List.of(new SalesReturnItemRequest(productId, batch, new BigDecimal("1"), warehouseId)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("已作废或未完成的出库单不可退货");
                });

        assertThat(salesReturnRepository.findByDeliveryId(delivery.id())).isEmpty();
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
    }
}
