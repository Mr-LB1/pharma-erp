package com.pharma.compliance;

import com.pharma.approval.dto.ApprovalPendingVO;
import com.pharma.approval.entity.ApprovalRecord;
import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
import com.pharma.purchase.dto.PurchaseReceiveVO;
import com.pharma.sales.dto.SalesDeliveryCreateRequest;
import com.pharma.sales.dto.SalesDeliveryItemRequest;
import com.pharma.sales.dto.SalesOrderCreateRequest;
import com.pharma.sales.dto.SalesOrderItemRequest;
import com.pharma.sales.dto.SalesOrderVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次一验收：审批前置（未审批不可出入库）、审批回避、驳回必填意见、
 * 订单生命周期（草稿编辑 / 驳回重提 / 作废终态）、作废出入库后订单状态回退。
 */
class ApprovalGuardTest extends AbstractComplianceTest {

    private static final String PO = "PURCHASE_ORDER";
    private static final String SO = "SALES_ORDER";

    @Test
    void 未审核订单不可入库也不可出库() {
        LocalDate today = LocalDate.now();
        String batch = "G1-" + System.nanoTime();

        PurchaseOrderVO po = createPurchaseOrder(new BigDecimal("10"));
        purchaseOrderService.submit(po.id());
        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("SUBMITTED");

        assertThatThrownBy(() -> purchaseReceiveService.create(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                        today.minusDays(5), today.plusDays(150), new BigDecimal("5"), "INS-G1")))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("待审批");
                });

        SalesOrderVO so = createSalesOrder(new BigDecimal("1"));
        salesOrderService.submit(so.id());
        assertThatThrownBy(() -> salesDeliveryService.create(new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("1"))))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("待审批");
                });
    }

    @Test
    void 审批人不能与制单人相同() {
        runAs("maker-a", () -> {
            PurchaseOrderVO po = createPurchaseOrderFor("maker-a", new BigDecimal("10"));
            purchaseOrderService.submit(po.id());

            assertThatThrownBy(() -> runAs("maker-a", () -> approvalService.approve(PO, po.id(), "自己批自己")))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(403);
                        assertThat(e.getMessage()).contains("制单人");
                    });

            // 换人审批通过（P1-1：需具备 APPROVER 角色）
            runAs("approver-b", List.of("ROLE_APPROVER"), () -> approvalService.approve(PO, po.id(), "同意"));
            assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("APPROVED");
        });
    }

    @Test
    void 驳回必须填写审批意见() {
        PurchaseOrderVO po = createPurchaseOrder(new BigDecimal("10"));
        purchaseOrderService.submit(po.id());

        assertThatThrownBy(() -> approvalService.reject(PO, po.id(), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> approvalService.reject(PO, po.id(), "   "))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(400));

        // 状态未被误改
        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("SUBMITTED");
    }

    @Test
    void 驳回后可编辑再重新提交() {
        runAs("maker-d", () -> {
            LocalDate today = LocalDate.now();
            PurchaseOrderVO po = createPurchaseOrderFor("maker-d", new BigDecimal("10"));
            purchaseOrderService.submit(po.id());

            runAs("approver-d", List.of("ROLE_APPROVER"),
                    () -> approvalService.reject(PO, po.id(), "价格有误，请调整"));
            assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("REJECTED");

            // 驳回态仍不可入库
            assertThatThrownBy(() -> purchaseReceiveService.create(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                    List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), "G3-" + System.nanoTime(),
                            today.minusDays(5), today.plusDays(150), new BigDecimal("5"), "INS-G3")))))
                    .isInstanceOf(BusinessException.class);

            // 方案 A：REJECTED 可编辑，改完仍是 REJECTED
            PurchaseOrderVO edited = purchaseOrderService.update(po.id(),
                    new PurchaseOrderCreateRequest(supplierId, today,
                            List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("12"), new BigDecimal("9")))));
            assertThat(edited.status()).isEqualTo("REJECTED");
            assertThat(edited.items()).hasSize(1);
            assertThat(edited.items().get(0).quantity()).isEqualByComparingTo("12");
            assertThat(edited.totalAmount()).isEqualByComparingTo("108.00");

            // REJECTED 可直接提交（写新的 PENDING 审批记录）
            PurchaseOrderVO resubmitted = purchaseOrderService.submit(po.id());
            assertThat(resubmitted.status()).isEqualTo("SUBMITTED");
            assertThat(resubmitted.items().get(0).quantity()).isEqualByComparingTo("12");

            List<ApprovalRecord> pendings = approvalRecordRepository.findByBizTypeAndBizIdAndStatus(PO, po.id(), "PENDING");
            assertThat(pendings).hasSize(1);

            approveAsApprover(PO, po.id());
            assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("APPROVED");

            // 已审核通过不可再重提
            assertThatThrownBy(() -> purchaseOrderService.resubmit(po.id()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getMessage()).contains("仅已驳回订单可重新提交"));

            // 已审核通过不可再编辑
            assertThatThrownBy(() -> purchaseOrderService.update(po.id(),
                    new PurchaseOrderCreateRequest(supplierId, today,
                            List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("1"), new BigDecimal("1"))))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getMessage()).contains("仅草稿或已驳回订单可编辑"));
        });
    }

    @Test
    void 草稿可编辑且明细整单重建() {
        PurchaseOrderVO po = createPurchaseOrder(new BigDecimal("10"));
        LocalDate today = LocalDate.now();

        PurchaseOrderVO updated = purchaseOrderService.update(po.id(),
                new PurchaseOrderCreateRequest(supplierId, today,
                        List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("20"), new BigDecimal("8")))));
        assertThat(updated.status()).isEqualTo("DRAFT");
        assertThat(updated.items()).hasSize(1);
        assertThat(updated.items().get(0).quantity()).isEqualByComparingTo("20");
        assertThat(updated.totalAmount()).isEqualByComparingTo("160.00");
    }

    @Test
    void 草稿与已提交订单可作废且为终态() {
        // 草稿作废
        PurchaseOrderVO draft = createPurchaseOrder(new BigDecimal("10"));
        assertThat(purchaseOrderService.voidOrder(draft.id(), "草稿误建").status()).isEqualTo("VOID");
        assertThatThrownBy(() -> purchaseOrderService.submit(draft.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("仅草稿或已驳回订单可提交"));
        assertThatThrownBy(() -> purchaseOrderService.voidOrder(draft.id(), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("仅草稿或已提交订单可作废"));

        // 已提交作废：待审批记录终止，且不可再审批
        PurchaseOrderVO submitted = createPurchaseOrder(new BigDecimal("10"));
        purchaseOrderService.submit(submitted.id());
        assertThat(purchaseOrderService.voidOrder(submitted.id(), "供应商取消").status()).isEqualTo("VOID");
        assertThat(approvalRecordRepository.findByBizTypeAndBizIdAndStatus(PO, submitted.id(), "PENDING")).isEmpty();
        assertThatThrownBy(() -> approvalService.approve(PO, submitted.id(), "作废后审批"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("仅已提交状态可审批"));

        // 销售订单同构
        SalesOrderVO so = createSalesOrder(new BigDecimal("3"));
        assertThat(salesOrderService.voidOrder(so.id(), "客户取消").status()).isEqualTo("VOID");
    }

    @Test
    void 作废入库单后订单状态回退() {
        LocalDate today = LocalDate.now();
        PurchaseOrderVO po = createPurchaseOrder(new BigDecimal("10"));
        purchaseOrderService.submit(po.id());
        approveAsApprover(PO, po.id());

        PurchaseReceiveVO receive = receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("4"), "G6-" + System.nanoTime(),
                        today.minusDays(5), today.plusDays(150), new BigDecimal("5"), "INS-G6"))));

        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("PARTIAL_RECEIVED");

        purchaseReceiveService.voidReceive(receive.id(), "回退验证");

        // 无有效入库单 -> 回退到 APPROVED（可再次入库）
        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("APPROVED");
        receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), "G6B-" + System.nanoTime(),
                        today.minusDays(5), today.plusDays(150), new BigDecimal("5"), "INS-G6B"))));
        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void 待审批列表带明细与制单人() {
        // 批次十：待审列表按审批人角色过滤，需以具备 APPROVER 角色的身份查看
        runAs("maker-c", List.of("approval:view", "ROLE_APPROVER"), () -> {
            PurchaseOrderVO po = createPurchaseOrderFor("maker-c", new BigDecimal("6"));
            purchaseOrderService.submit(po.id());

            PageResult<ApprovalPendingVO> page = approvalService.pending(1, 200);
            ApprovalPendingVO target = page.getRecords().stream()
                    .filter(r -> PO.equals(r.bizType()) && po.id().equals(r.bizId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("待审批列表未包含订单 " + po.orderNo()));

            assertThat(target.createdBy()).isEqualTo("maker-c");
            assertThat(target.items()).hasSize(1);
            assertThat(target.items().get(0).productName()).contains("测试药品");
            assertThat(target.items().get(0).quantity()).isEqualByComparingTo("6");
            assertThat(target.items().get(0).unitPrice()).isEqualByComparingTo("10");
        });
    }

    private PurchaseOrderVO createPurchaseOrder(BigDecimal quantity) {
        return createPurchaseOrderFor(null, quantity);
    }

    private PurchaseOrderVO createPurchaseOrderFor(String username, BigDecimal quantity) {
        LocalDate today = LocalDate.now();
        PurchaseOrderCreateRequest req = new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, quantity, new BigDecimal("10"))));
        if (username == null) {
            return purchaseOrderService.create(req);
        }
        PurchaseOrderVO[] holder = new PurchaseOrderVO[1];
        runAs(username, () -> holder[0] = purchaseOrderService.create(req));
        return holder[0];
    }

    private SalesOrderVO createSalesOrder(BigDecimal quantity) {
        LocalDate today = LocalDate.now();
        return salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, quantity, new BigDecimal("20")))));
    }
}
