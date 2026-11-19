package com.pharma.finance;

import com.pharma.common.BusinessException;
import com.pharma.finance.dto.PaymentCreateRequest;
import com.pharma.finance.dto.PaymentItemRequest;
import com.pharma.finance.dto.ReceiptCreateRequest;
import com.pharma.finance.dto.ReceiptItemRequest;
import com.pharma.finance.dto.StatementVO;
import com.pharma.finance.entity.ApInvoice;
import com.pharma.finance.entity.ArInvoice;
import com.pharma.finance.entity.Payment;
import com.pharma.finance.entity.Receipt;
import com.pharma.finance.repository.ApInvoiceRepository;
import com.pharma.finance.repository.ArInvoiceRepository;
import com.pharma.finance.repository.PaymentRepository;
import com.pharma.finance.repository.ReceiptRepository;
import com.pharma.finance.service.ArApService;
import com.pharma.finance.service.PaymentService;
import com.pharma.finance.service.ReceiptService;
import com.pharma.finance.service.StatementService;
import com.pharma.inventory.AbstractInventoryTest;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 财务 AR/AP（P0-5）：出库生应收 / 入库生应付 / 作废联动 / 信用拦截 / 收付款核销流转 / 对账平衡。
 */
class FinanceArApTest extends AbstractInventoryTest {

    @Autowired
    private ArApService arApService;
    @Autowired
    private ReceiptService receiptService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private StatementService statementService;
    @Autowired
    private ArInvoiceRepository arInvoiceRepository;
    @Autowired
    private ApInvoiceRepository apInvoiceRepository;
    @Autowired
    private ReceiptRepository receiptRepository;
    @Autowired
    private PaymentRepository paymentRepository;

    private static final String BATCH = "FIN-" + System.nanoTime();

    @Test
    void 销售出库自动生成应收() {
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesDeliveryVO delivery = deliver(new BigDecimal("10"), new BigDecimal("20"));

        List<ArInvoice> invoices = arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id());
        assertThat(invoices).hasSize(1);
        ArInvoice ar = invoices.get(0);
        assertThat(ar.getCustomerId()).isEqualTo(customerId);
        assertThat(ar.getInvoiceAmount()).isEqualByComparingTo("200");
        assertThat(ar.getSettledAmount()).isEqualByComparingTo("0");
        assertThat(ar.getStatus()).isEqualTo("UNSETTLED");
        assertThat(ar.getInvoiceNo()).startsWith("AR");
        assertThat(arApService.outstanding(customerId)).isEqualByComparingTo("200");
    }

    @Test
    void 采购入库自动生成应付() {
        PurchaseReceiveVO receive = receive(new BigDecimal("10"), new BigDecimal("5"));

        List<ApInvoice> invoices = apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", receive.id());
        assertThat(invoices).hasSize(1);
        ApInvoice ap = invoices.get(0);
        assertThat(ap.getSupplierId()).isEqualTo(supplierId);
        assertThat(ap.getInvoiceAmount()).isEqualByComparingTo("50");
        assertThat(ap.getStatus()).isEqualTo("UNSETTLED");
        assertThat(ap.getInvoiceNo()).startsWith("AP");
        assertThat(arApService.outstandingPayable(supplierId)).isEqualByComparingTo("50");
    }

    @Test
    void 作废出库联动应收VOID() {
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesDeliveryVO delivery = deliver(new BigDecimal("10"), new BigDecimal("20"));

        salesDeliveryService.voidDelivery(delivery.id(), "测试作废");

        ArInvoice ar = arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id()).get(0);
        assertThat(ar.getStatus()).isEqualTo("VOID");
        assertThat(arApService.outstanding(customerId)).isEqualByComparingTo("0");
    }

    @Test
    void 作废入库联动应付VOID() {
        PurchaseReceiveVO receive = receive(new BigDecimal("10"), new BigDecimal("5"));

        purchaseReceiveService.voidReceive(receive.id(), "测试作废");

        ApInvoice ap = apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", receive.id()).get(0);
        assertThat(ap.getStatus()).isEqualTo("VOID");
        assertThat(arApService.outstandingPayable(supplierId)).isEqualByComparingTo("0");
    }

    @Test
    void 超出信用额度提审被拒绝400() {
        com.pharma.base.entity.Customer customer = customerRepository.findById(customerId).orElseThrow();
        customer.setCreditLimit(new BigDecimal("100"));
        customerRepository.save(customer);

        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesOrderVO order = salesOrderService.create(new SalesOrderCreateRequest(customerId, LocalDate.now(),
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20")))));

        assertThatThrownBy(() -> salesOrderService.submit(order.id()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("超出信用额度");
                });
    }

    @Test
    void 信用额度为空视为不限() {
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesOrderVO order = salesOrderService.create(new SalesOrderCreateRequest(customerId, LocalDate.now(),
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("9999")))));

        SalesOrderVO submitted = salesOrderService.submit(order.id());
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void 收款核销状态流转UNSETTLED到PARTIAL到SETTLED() {
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesDeliveryVO delivery = deliver(new BigDecimal("10"), new BigDecimal("20"));
        ArInvoice ar = arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id()).get(0);

        receiptService.create(new ReceiptCreateRequest(customerId, new BigDecimal("80"), "首付",
                List.of(new ReceiptItemRequest(ar.getId(), new BigDecimal("80")))));
        ArInvoice partial = arInvoiceRepository.findById(ar.getId()).orElseThrow();
        assertThat(partial.getSettledAmount()).isEqualByComparingTo("80");
        assertThat(partial.getStatus()).isEqualTo("PARTIAL");

        Receipt second = receiptRepository.findAll().stream()
                .max(java.util.Comparator.comparing(Receipt::getId)).orElseThrow();
        assertThat(second.getAmount()).isEqualByComparingTo("80");
        assertThat(second.getReceiptNo()).startsWith("SK");

        receiptService.create(new ReceiptCreateRequest(customerId, new BigDecimal("120"), "尾款",
                List.of(new ReceiptItemRequest(ar.getId(), new BigDecimal("120")))));
        ArInvoice settled = arInvoiceRepository.findById(ar.getId()).orElseThrow();
        assertThat(settled.getSettledAmount()).isEqualByComparingTo("200");
        assertThat(settled.getStatus()).isEqualTo("SETTLED");
        assertThat(arApService.outstanding(customerId)).isEqualByComparingTo("0");

        assertThatThrownBy(() -> receiptService.create(new ReceiptCreateRequest(customerId, new BigDecimal("10"), "超收",
                List.of(new ReceiptItemRequest(ar.getId(), new BigDecimal("10"))))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 付款核销状态流转() {
        PurchaseReceiveVO receive = receive(new BigDecimal("10"), new BigDecimal("5"));
        ApInvoice ap = apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", receive.id()).get(0);

        paymentService.create(new PaymentCreateRequest(supplierId, new BigDecimal("30"), "预付",
                List.of(new PaymentItemRequest(ap.getId(), new BigDecimal("30")))));
        assertThat(apInvoiceRepository.findById(ap.getId()).orElseThrow().getStatus()).isEqualTo("PARTIAL");

        paymentService.create(new PaymentCreateRequest(supplierId, new BigDecimal("20"), "尾款",
                List.of(new PaymentItemRequest(ap.getId(), new BigDecimal("20")))));
        assertThat(apInvoiceRepository.findById(ap.getId()).orElseThrow().getStatus()).isEqualTo("SETTLED");

        Payment payment = paymentRepository.findAll().stream()
                .max(java.util.Comparator.comparing(Payment::getId)).orElseThrow();
        assertThat(payment.getPaymentNo()).startsWith("FK");
        assertThat(arApService.outstandingPayable(supplierId)).isEqualByComparingTo("0");
    }

    @Test
    void 客户对账期初期末平衡() {
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesDeliveryVO delivery = deliver(new BigDecimal("10"), new BigDecimal("20"));
        ArInvoice ar = arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id()).get(0);
        receiptService.create(new ReceiptCreateRequest(customerId, new BigDecimal("50"), "部分收款",
                List.of(new ReceiptItemRequest(ar.getId(), new BigDecimal("50")))));

        LocalDate today = LocalDate.now();
        StatementVO statement = statementService.ar(customerId, today, today);

        assertThat(statement.openingBalance()).isEqualByComparingTo("0");
        assertThat(statement.deliveryAmount()).isEqualByComparingTo("200");
        assertThat(statement.returnAmount()).isEqualByComparingTo("0");
        assertThat(statement.receiptAmount()).isEqualByComparingTo("50");
        assertThat(statement.closingBalance()).isEqualByComparingTo("150");
        assertThat(statement.closingBalance()).isEqualByComparingTo(
                statement.openingBalance().add(statement.deliveryAmount())
                        .subtract(statement.returnAmount()).subtract(statement.receiptAmount()));
        assertThat(statement.items()).isNotEmpty();
    }

    @Test
    void 对账单按业务单据日期分期而非入账时间() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        createStock(BATCH, today.plusDays(120), new BigDecimal("100"), new BigDecimal("5"));

        // 出库业务日期为昨天（created_at 为今天）
        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20")))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, so.id(), yesterday,
                List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("10")))));

        // 今天：期初 200、本期 0、期末 200（若按 created_at 会误记为本期发货）
        StatementVO todayStmt = statementService.ar(customerId, today, today);
        assertThat(todayStmt.deliveryAmount()).isEqualByComparingTo("0");
        assertThat(todayStmt.openingBalance()).isEqualByComparingTo("200");
        assertThat(todayStmt.closingBalance()).isEqualByComparingTo("200");

        // 昨天：本期发货 200
        StatementVO yStmt = statementService.ar(customerId, yesterday, yesterday);
        assertThat(yStmt.openingBalance()).isEqualByComparingTo("0");
        assertThat(yStmt.deliveryAmount()).isEqualByComparingTo("200");
        assertThat(yStmt.closingBalance()).isEqualByComparingTo("200");
        assertThat(yStmt.items()).isNotEmpty();
        assertThat(yStmt.items().get(0).date()).isEqualTo(yesterday);
    }

    private PurchaseReceiveVO receive(BigDecimal qty, BigDecimal cost) {
        LocalDate today = LocalDate.now();
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, qty, cost))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        return receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, qty, BATCH,
                        today.minusDays(10), today.plusDays(120), cost, "INS-FIN"))));
    }

    private SalesDeliveryVO deliver(BigDecimal qty, BigDecimal price) {
        LocalDate today = LocalDate.now();
        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, qty, price))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        return deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                List.of(new SalesDeliveryItemRequest(productId, qty))));
    }
}
