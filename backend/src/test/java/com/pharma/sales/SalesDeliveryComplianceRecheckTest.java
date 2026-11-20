package com.pharma.sales;

import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Product;
import com.pharma.common.BusinessException;
import com.pharma.finance.repository.ArInvoiceRepository;
import com.pharma.inventory.AbstractInventoryTest;
import com.pharma.sales.dto.SalesDeliveryCreateRequest;
import com.pharma.sales.dto.SalesDeliveryItemRequest;
import com.pharma.sales.dto.SalesDeliveryVO;
import com.pharma.sales.dto.SalesOrderCreateRequest;
import com.pharma.sales.dto.SalesOrderItemRequest;
import com.pharma.sales.dto.SalesOrderVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-F 销售 WMS 执行前合规复核回归：建单/审批时已过首营，但审批后到 WMS 回传之间
 * 客户证照过期、商品停用或商品首营被撤销时，执行出库必须 400，且不扣库存、不生成应收。
 * 覆盖链路：wmsCallback（锁单据头）→ executeActual → checkComplianceBeforeExecute。
 */
class SalesDeliveryComplianceRecheckTest extends AbstractInventoryTest {

    @Autowired
    private ArInvoiceRepository arInvoiceRepository;

    private static final String BATCH = "P1F-" + System.nanoTime();

    @Test
    void 客户证照过期后WMS回传被拒绝400且不扣库存不生成应收() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-CUST";
        createStock(batch, today.plusDays(180), new BigDecimal("10"), new BigDecimal("5"));
        SalesOrderVO so = approvedOrder(today, new BigDecimal("5"));
        SalesDeliveryVO delivery = pushedDelivery(so.id(), today, new BigDecimal("5"));
        assertThat(delivery.status()).isEqualTo("PUSHED");

        // 订单审批通过后客户证照过期（建单时有效）
        Customer customer = customerRepository.findById(customerId).orElseThrow();
        String customerName = customer.getName();
        customer.setLicenseExpire(today.minusDays(1));
        customerRepository.save(customer);

        assertThatThrownBy(() -> salesDeliveryService.wmsCallback(delivery.id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("客户证照已过期").contains(customerName);
                });

        // 未扣库存、未生成应收、单据未推进、订单状态未变
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
        assertThat(arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id())).isEmpty();
        assertThat(salesDeliveryService.get(delivery.id()).status()).isEqualTo("PUSHED");
        assertThat(salesOrderService.get(so.id()).status()).isEqualTo("APPROVED");
    }

    @Test
    void 商品停用后WMS回传被拒绝400且不扣库存不生成应收() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-STOP";
        createStock(batch, today.plusDays(180), new BigDecimal("10"), new BigDecimal("5"));
        SalesOrderVO so = approvedOrder(today, new BigDecimal("5"));
        SalesDeliveryVO delivery = pushedDelivery(so.id(), today, new BigDecimal("5"));

        // 订单审批通过后商品被停用
        Product product = productRepository.findById(productId).orElseThrow();
        String productName = product.getGenericName();
        product.setStatus("INACTIVE");
        productRepository.save(product);

        assertThatThrownBy(() -> salesDeliveryService.wmsCallback(delivery.id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("商品已停用").contains(productName);
                });

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
        assertThat(arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id())).isEmpty();
        assertThat(salesDeliveryService.get(delivery.id()).status()).isEqualTo("PUSHED");
    }

    @Test
    void 商品首营撤销后WMS回传被拒绝400且不扣库存不生成应收() {
        LocalDate today = LocalDate.now();
        String batch = BATCH + "-AUDIT";
        createStock(batch, today.plusDays(180), new BigDecimal("10"), new BigDecimal("5"));
        SalesOrderVO so = approvedOrder(today, new BigDecimal("5"));
        SalesDeliveryVO delivery = pushedDelivery(so.id(), today, new BigDecimal("5"));

        // 订单审批通过后商品首营被撤销（退回 PENDING）
        Product product = productRepository.findById(productId).orElseThrow();
        String productName = product.getGenericName();
        product.setFirstAuditStatus("PENDING");
        productRepository.save(product);

        assertThatThrownBy(() -> salesDeliveryService.wmsCallback(delivery.id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("商品未过首营审核").contains(productName);
                });

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
        assertThat(arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id())).isEmpty();
        assertThat(salesDeliveryService.get(delivery.id()).status()).isEqualTo("PUSHED");
    }

    /** 销售订单：建单 → 提审 → 审批通过（APPROVED），客户/商品此时均合规。 */
    private SalesOrderVO approvedOrder(LocalDate today, BigDecimal qty) {
        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, qty, new BigDecimal("20")))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        return so;
    }

    /** 销售出库：建单 → 提审 → 审批通过（PUSHED，等待 WMS 回传），不执行出库。 */
    private SalesDeliveryVO pushedDelivery(Long orderId, LocalDate today, BigDecimal qty) {
        SalesDeliveryVO created = salesDeliveryService.create(new SalesDeliveryCreateRequest(warehouseId, orderId,
                today, List.of(new SalesDeliveryItemRequest(productId, qty))));
        salesDeliveryService.submit(created.id());
        approveAsApprover("SALES_DELIVERY", created.id());
        return salesDeliveryService.get(created.id());
    }
}
