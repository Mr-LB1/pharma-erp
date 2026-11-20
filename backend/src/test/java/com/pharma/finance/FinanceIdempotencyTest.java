package com.pharma.finance;

import com.pharma.common.BusinessException;
import com.pharma.finance.dto.ApInvoiceVO;
import com.pharma.finance.dto.ArInvoiceVO;
import com.pharma.finance.dto.PaymentCreateRequest;
import com.pharma.finance.dto.PaymentItemRequest;
import com.pharma.finance.dto.PaymentVO;
import com.pharma.finance.dto.ReceiptCreateRequest;
import com.pharma.finance.dto.ReceiptItemRequest;
import com.pharma.finance.dto.ReceiptVO;
import com.pharma.finance.entity.ApInvoice;
import com.pharma.finance.entity.ArInvoice;
import com.pharma.finance.repository.ApInvoiceRepository;
import com.pharma.finance.repository.ArInvoiceRepository;
import com.pharma.finance.repository.PaymentRepository;
import com.pharma.finance.repository.ReceiptRepository;
import com.pharma.finance.service.ArApService;
import com.pharma.finance.service.PaymentService;
import com.pharma.finance.service.ReceiptService;
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
import com.pharma.system.entity.AuditLog;
import com.pharma.system.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 财务核销幂等、作废保护与审计留痕回归测试（P0-3 / P1-A / P1-G）：
 * 同一发票在收/付款明细中重复出现必须 400；已发生核销的应收/应付禁止随业务单据作废（400）；
 * 作废联动必须加锁重读，不得使用持久化上下文缓存的旧已核销金额放行（TOCTOU 回归）；
 * 收付款核销、手工补录应收应付、作废发票均须写 audit_log 留痕。
 */
class FinanceIdempotencyTest extends AbstractInventoryTest {

    /** 收款核销服务：验证重复核销明细拦截与多应收单正常累加。 */
    @Autowired
    private ReceiptService receiptService;

    /** 付款核销服务：验证重复核销明细拦截。 */
    @Autowired
    private PaymentService paymentService;

    /** 应收单仓库：取自动生成的应收单并校验已核销金额。 */
    @Autowired
    private ArInvoiceRepository arInvoiceRepository;

    /** 应付单仓库：取自动生成的应付单并校验状态。 */
    @Autowired
    private ApInvoiceRepository apInvoiceRepository;

    /** 收款单仓库：校验重复明细被拦截时未落收款单。 */
    @Autowired
    private ReceiptRepository receiptRepository;

    /** 付款单仓库：校验重复明细被拦截时未落付款单。 */
    @Autowired
    private PaymentRepository paymentRepository;

    /** 审计日志仓库：断言收付款、手工补录与作废留痕已写入 audit_log。 */
    @Autowired
    private AuditLogRepository auditLogRepository;

    /** 应收应付服务：校验作废后客户未结应收清零。 */
    @Autowired
    private ArApService arApService;

    /** JPA 实体管理器：模拟并发事务已提交的核销，绕过一级缓存直接改库做 TOCTOU 回归。 */
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    /** 测试批号：按纳秒生成，避免同库测试数据互相干扰。 */
    private static final String BATCH = "FIN-IDEM-" + System.nanoTime();

    /**
     * 用例：同一张应收单在一张收款单明细中重复出现时，直接返回 400 且不落收款单。
     */
    @Test
    void 收款明细同一应收单重复直接400() {
        // 1. 造数：出库自动生成应收 200
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesDeliveryVO delivery = deliver(new BigDecimal("10"), new BigDecimal("20"));
        ArInvoice arInvoice = arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id()).get(0);
        long receiptCountBefore = receiptRepository.count();

        // 2. 执行：同一应收单拆成两行（50 + 50）核销，金额合计与收款金额一致
        assertThatThrownBy(() -> receiptService.create(new ReceiptCreateRequest(customerId,
                new BigDecimal("100"), "重复核销",
                List.of(new ReceiptItemRequest(arInvoice.getId(), new BigDecimal("50")),
                        new ReceiptItemRequest(arInvoice.getId(), new BigDecimal("50"))))))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    assertThat(((BusinessException) exception).getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("重复核销");
                });

        // 3. 断言：未生成收款单，应收已核销金额未变
        assertThat(receiptRepository.count()).isEqualTo(receiptCountBefore);
        assertThat(arInvoiceRepository.findById(arInvoice.getId()).orElseThrow().getSettledAmount())
                .isEqualByComparingTo("0");
    }

    /**
     * 用例：同一张应付单在一张付款单明细中重复出现时，直接返回 400 且不落付款单。
     */
    @Test
    void 付款明细同一应付单重复直接400() {
        // 1. 造数：入库自动生成应付 50
        PurchaseReceiveVO receive = receive(new BigDecimal("10"), new BigDecimal("5"));
        ApInvoice apInvoice = apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", receive.id()).get(0);
        long paymentCountBefore = paymentRepository.count();

        // 2. 执行：同一应付单拆成两行（30 + 20）核销，金额合计与付款金额一致
        assertThatThrownBy(() -> paymentService.create(new PaymentCreateRequest(supplierId,
                new BigDecimal("50"), "重复核销",
                List.of(new PaymentItemRequest(apInvoice.getId(), new BigDecimal("30")),
                        new PaymentItemRequest(apInvoice.getId(), new BigDecimal("20"))))))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    assertThat(((BusinessException) exception).getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("重复核销");
                });

        // 3. 断言：未生成付款单，应付已核销金额未变
        assertThat(paymentRepository.count()).isEqualTo(paymentCountBefore);
        assertThat(apInvoiceRepository.findById(apInvoice.getId()).orElseThrow().getSettledAmount())
                .isEqualByComparingTo("0");
    }

    /**
     * 用例：一张收款单同时核销两张不同应收单时正常累加（去重与加锁不误伤合法多明细）。
     */
    @Test
    void 收款单多张应收单正常核销() {
        // 1. 造数：两次出库各生成应收 200
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesDeliveryVO firstDelivery = deliver(new BigDecimal("10"), new BigDecimal("20"));
        SalesDeliveryVO secondDelivery = deliver(new BigDecimal("10"), new BigDecimal("20"));
        ArInvoice firstInvoice = arInvoiceRepository
                .findByBizTypeAndBizId("SALES_DELIVERY", firstDelivery.id()).get(0);
        ArInvoice secondInvoice = arInvoiceRepository
                .findByBizTypeAndBizId("SALES_DELIVERY", secondDelivery.id()).get(0);

        // 2. 执行：一张收款单两行明细，分别核销两张应收单
        receiptService.create(new ReceiptCreateRequest(customerId, new BigDecimal("200"), "多单核销",
                List.of(new ReceiptItemRequest(firstInvoice.getId(), new BigDecimal("120")),
                        new ReceiptItemRequest(secondInvoice.getId(), new BigDecimal("80")))));

        // 3. 断言：两张应收单各自累加已核销金额并推进状态
        assertThat(arInvoiceRepository.findById(firstInvoice.getId()).orElseThrow().getSettledAmount())
                .isEqualByComparingTo("120");
        assertThat(arInvoiceRepository.findById(firstInvoice.getId()).orElseThrow().getStatus())
                .isEqualTo("PARTIAL");
        assertThat(arInvoiceRepository.findById(secondInvoice.getId()).orElseThrow().getSettledAmount())
                .isEqualByComparingTo("80");
        assertThat(arInvoiceRepository.findById(secondInvoice.getId()).orElseThrow().getStatus())
                .isEqualTo("PARTIAL");
    }

    /**
     * 用例：应收单已部分收款后作废销售出库，应拒绝并返回 400（已核销不能作废）。
     */
    @Test
    void 已核销应收禁止作废出库400() {
        // 1. 造数：出库生成应收 200，收款核销 50 使其进入 PARTIAL
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesDeliveryVO delivery = deliver(new BigDecimal("10"), new BigDecimal("20"));
        ArInvoice arInvoice = arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id()).get(0);
        receiptService.create(new ReceiptCreateRequest(customerId, new BigDecimal("50"), "部分收款",
                List.of(new ReceiptItemRequest(arInvoice.getId(), new BigDecimal("50")))));

        // 2. 执行 + 断言：作废出库被财务联动拦截，错误信息说明已核销不能作废
        assertThatThrownBy(() -> salesDeliveryService.voidDelivery(delivery.id(), "已核销测试作废"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    assertThat(((BusinessException) exception).getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("已核销不能作废");
                });
    }

    /**
     * 用例：应付单已部分付款后作废采购入库，应拒绝并返回 400（已核销不能作废）。
     */
    @Test
    void 已核销应付禁止作废入库400() {
        // 1. 造数：入库生成应付 50，付款核销 30 使其进入 PARTIAL
        PurchaseReceiveVO receive = receive(new BigDecimal("10"), new BigDecimal("5"));
        ApInvoice apInvoice = apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", receive.id()).get(0);
        paymentService.create(new PaymentCreateRequest(supplierId, new BigDecimal("30"), "部分付款",
                List.of(new PaymentItemRequest(apInvoice.getId(), new BigDecimal("30")))));

        // 2. 执行 + 断言：作废入库被财务联动拦截
        assertThatThrownBy(() -> purchaseReceiveService.voidReceive(receive.id(), "已核销测试作废"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    assertThat(((BusinessException) exception).getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("已核销不能作废");
                });
    }

    /**
     * 用例：应收单未发生核销时作废销售出库应成功，出库单与应收单均置 VOID 且未结应收清零。
     */
    @Test
    void 未核销应收可正常作废出库() {
        // 1. 造数：出库生成应收 200，未发生核销
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesDeliveryVO delivery = deliver(new BigDecimal("10"), new BigDecimal("20"));
        ArInvoice arInvoice = arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id()).get(0);

        // 2. 执行：作废出库（财务联动将应收置 VOID）
        SalesDeliveryVO voided = salesDeliveryService.voidDelivery(delivery.id(), "未核销作废");

        // 3. 断言：出库单与应收单均 VOID，客户未结应收清零
        assertThat(voided.status()).isEqualTo("VOID");
        assertThat(arInvoiceRepository.findById(arInvoice.getId()).orElseThrow().getStatus()).isEqualTo("VOID");
        assertThat(arApService.outstanding(customerId)).isEqualByComparingTo("0");
    }

    /**
     * 用例（TOCTOU 回归）：应收单已在一级缓存中为「未核销」、随后并发事务已提交核销时，
     * 作废出库必须在加锁重读后返回 400，不能拿缓存中的旧 settled_amount 放行。
     */
    @Test
    void 缓存未核销但库中已核销时作废应收400() {
        // 1. 造数：出库生成应收 200，并确认其以 settled=0 进入当前持久化上下文
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesDeliveryVO delivery = deliver(new BigDecimal("10"), new BigDecimal("20"));
        ArInvoice cachedInvoice = arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id()).get(0);
        assertThat(cachedInvoice.getSettledAmount()).isEqualByComparingTo("0");

        // 2. 模拟并发事务已提交核销：绕过持久化上下文直接改库（一级缓存仍是旧的 settled=0）
        entityManager.flush();
        entityManager.createNativeQuery("update ar_invoice set settled_amount = 50, status = 'PARTIAL' where id = ?1")
                .setParameter(1, cachedInvoice.getId())
                .executeUpdate();

        // 3. 执行 + 断言：作废必须锁后重读最新已核销金额并拒绝
        assertThatThrownBy(() -> salesDeliveryService.voidDelivery(delivery.id(), "缓存旧值测试作废"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    assertThat(((BusinessException) exception).getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("已核销不能作废");
                });
    }

    /**
     * 用例（TOCTOU 回归）：应付单已在一级缓存中为「未核销」、随后并发事务已提交核销时，
     * 作废采购入库必须在加锁重读后返回 400。
     */
    @Test
    void 缓存未核销但库中已核销时作废应付400() {
        // 1. 造数：入库生成应付 50，并确认其以 settled=0 进入当前持久化上下文
        PurchaseReceiveVO receive = receive(new BigDecimal("10"), new BigDecimal("5"));
        ApInvoice cachedInvoice = apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", receive.id()).get(0);
        assertThat(cachedInvoice.getSettledAmount()).isEqualByComparingTo("0");

        // 2. 模拟并发事务已提交核销：绕过持久化上下文直接改库（一级缓存仍是旧的 settled=0）
        entityManager.flush();
        entityManager.createNativeQuery("update ap_invoice set settled_amount = 30, status = 'PARTIAL' where id = ?1")
                .setParameter(1, cachedInvoice.getId())
                .executeUpdate();

        // 3. 执行 + 断言：作废必须锁后重读最新已核销金额并拒绝
        assertThatThrownBy(() -> purchaseReceiveService.voidReceive(receive.id(), "缓存旧值测试作废"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    assertThat(((BusinessException) exception).getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("已核销不能作废");
                });
    }

    /**
     * 用例：创建收款单/付款单应各写一条审计日志，包含单号、往来单位、金额与核销明细。
     */
    @Test
    void 收付款创建写审计日志() {
        // 1. 造数：出库生成应收并收款核销 50；入库生成应付并付款核销 30
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesDeliveryVO delivery = deliver(new BigDecimal("10"), new BigDecimal("20"));
        ArInvoice arInvoice = arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id()).get(0);
        ReceiptVO receipt = receiptService.create(new ReceiptCreateRequest(customerId, new BigDecimal("50"), "审计收款",
                List.of(new ReceiptItemRequest(arInvoice.getId(), new BigDecimal("50")))));

        PurchaseReceiveVO receive = receive(new BigDecimal("10"), new BigDecimal("5"));
        ApInvoice apInvoice = apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", receive.id()).get(0);
        PaymentVO payment = paymentService.create(new PaymentCreateRequest(supplierId, new BigDecimal("30"), "审计付款",
                List.of(new PaymentItemRequest(apInvoice.getId(), new BigDecimal("30")))));

        // 2. 断言收款审计：动作/对象/单号/客户/金额/核销明细齐全
        List<AuditLog> receiptLogs = auditLogRepository
                .findByActionAndObjectTypeAndObjectIdOrderByIdDesc("CREATE_RECEIPT", "RECEIPT", receipt.id());
        assertThat(receiptLogs).hasSize(1);
        AuditLog receiptLog = receiptLogs.get(0);
        assertThat(receiptLog.getUsername()).isNotBlank();
        assertThat(receiptLog.getDetail())
                .contains(receipt.receiptNo())
                .contains("金额 50.00")
                .contains(arInvoice.getInvoiceNo());
        assertThat(receiptLog.getAfterValue())
                .contains(receipt.receiptNo())
                .contains(String.valueOf(customerId));

        // 3. 断言付款审计：动作/对象/单号/供应商/金额/核销明细齐全
        List<AuditLog> paymentLogs = auditLogRepository
                .findByActionAndObjectTypeAndObjectIdOrderByIdDesc("CREATE_PAYMENT", "PAYMENT", payment.id());
        assertThat(paymentLogs).hasSize(1);
        AuditLog paymentLog = paymentLogs.get(0);
        assertThat(paymentLog.getUsername()).isNotBlank();
        assertThat(paymentLog.getDetail())
                .contains(payment.paymentNo())
                .contains("金额 30.00")
                .contains(apInvoice.getInvoiceNo());
        assertThat(paymentLog.getAfterValue())
                .contains(payment.paymentNo())
                .contains(String.valueOf(supplierId));
    }

    /**
     * 用例：手工补录应收/应付应各写一条审计日志，包含发票号、往来单位与金额。
     */
    @Test
    void 手工补录应收应付写审计日志() {
        // 1. 手工补录应收 100、应付 60
        ArInvoiceVO manualAr = arApService.createManualAr(customerId, new BigDecimal("100"), "手工应收测试");
        ApInvoiceVO manualAp = arApService.createManualAp(supplierId, new BigDecimal("60"), "手工应付测试");

        // 2. 断言应收补录审计：发票号、客户、金额、备注齐全
        List<AuditLog> arLogs = auditLogRepository
                .findByActionAndObjectTypeAndObjectIdOrderByIdDesc("CREATE_MANUAL_AR", "AR_INVOICE", manualAr.id());
        assertThat(arLogs).hasSize(1);
        assertThat(arLogs.get(0).getDetail())
                .contains(manualAr.invoiceNo())
                .contains("金额 100.00")
                .contains("手工应收测试");

        // 3. 断言应付补录审计：发票号、供应商、金额、备注齐全
        List<AuditLog> apLogs = auditLogRepository
                .findByActionAndObjectTypeAndObjectIdOrderByIdDesc("CREATE_MANUAL_AP", "AP_INVOICE", manualAp.id());
        assertThat(apLogs).hasSize(1);
        assertThat(apLogs.get(0).getDetail())
                .contains(manualAp.invoiceNo())
                .contains("金额 60.00")
                .contains("手工应付测试");
    }

    /**
     * 用例：作废出库/入库联动作废应收/应付时，每张被作废发票应写一条审计日志（发票号、业务单据、金额）。
     */
    @Test
    void 作废发票写审计日志() {
        // 1. 造数：出库生成应收 200 后作废出库；入库生成应付 50 后作废入库
        createStock(BATCH, LocalDate.now().plusDays(120), new BigDecimal("100"), new BigDecimal("5"));
        SalesDeliveryVO delivery = deliver(new BigDecimal("10"), new BigDecimal("20"));
        ArInvoice arInvoice = arInvoiceRepository.findByBizTypeAndBizId("SALES_DELIVERY", delivery.id()).get(0);
        salesDeliveryService.voidDelivery(delivery.id(), "审计作废出库");

        PurchaseReceiveVO receive = receive(new BigDecimal("10"), new BigDecimal("5"));
        ApInvoice apInvoice = apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RECEIVE", receive.id()).get(0);
        purchaseReceiveService.voidReceive(receive.id(), "审计作废入库");

        // 2. 断言应收作废审计：发票号、业务单据类型:ID、金额、状态 VOID
        List<AuditLog> arLogs = auditLogRepository
                .findByActionAndObjectTypeAndObjectIdOrderByIdDesc("VOID_AR_INVOICE", "AR_INVOICE", arInvoice.getId());
        assertThat(arLogs).hasSize(1);
        assertThat(arLogs.get(0).getDetail())
                .contains(arInvoice.getInvoiceNo())
                .contains("SALES_DELIVERY:" + delivery.id())
                .contains("金额 200.00");
        assertThat(arLogs.get(0).getAfterValue()).contains("VOID");

        // 3. 断言应付作废审计：发票号、业务单据类型:ID、金额
        List<AuditLog> apLogs = auditLogRepository
                .findByActionAndObjectTypeAndObjectIdOrderByIdDesc("VOID_AP_INVOICE", "AP_INVOICE", apInvoice.getId());
        assertThat(apLogs).hasSize(1);
        assertThat(apLogs.get(0).getDetail())
                .contains(apInvoice.getInvoiceNo())
                .contains("PURCHASE_RECEIVE:" + receive.id())
                .contains("金额 50.00");
    }

    /**
     * 构造并执行一次采购入库全流程：订单 → 提交 → 审批 → WMS 回传，返回已完成的入库单。
     *
     * @param qty  入库数量
     * @param cost 采购成本单价
     * @return 已完成的采购入库单视图
     */
    private PurchaseReceiveVO receive(BigDecimal qty, BigDecimal cost) {
        LocalDate today = LocalDate.now();
        PurchaseOrderVO purchaseOrder = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, qty, cost))));
        purchaseOrderService.submit(purchaseOrder.id());
        approveAsApprover("PURCHASE_ORDER", purchaseOrder.id());
        return receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, purchaseOrder.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, qty, BATCH,
                        today.minusDays(10), today.plusDays(120), cost, "INS-FIN-IDEM"))));
    }

    /**
     * 构造并执行一次销售出库全流程：订单 → 提交 → 审批 → WMS 回传，返回已完成的出库单。
     *
     * @param qty   出库数量
     * @param price 销售单价
     * @return 已完成的销售出库单视图
     */
    private SalesDeliveryVO deliver(BigDecimal qty, BigDecimal price) {
        LocalDate today = LocalDate.now();
        SalesOrderVO salesOrder = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, qty, price))));
        salesOrderService.submit(salesOrder.id());
        approveAsApprover("SALES_ORDER", salesOrder.id());
        return deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, salesOrder.id(), today,
                List.of(new SalesDeliveryItemRequest(productId, qty))));
    }
}
