package com.pharma.compliance;

import com.pharma.approval.service.ApprovalLevelResolver;
import com.pharma.common.BusinessException;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-1 金额分级审批：approval_config 命中区间决定待审节点（L1/L2）与所需审批角色；
 * 角色不符 403，ADMIN 豁免，无配置回落 L1/APPROVER。
 */
class ApprovalLevelTest extends AbstractComplianceTest {

    private static final String PO = "PURCHASE_ORDER";

    @Autowired
    private ApprovalLevelResolver approvalLevelResolver;

    @Test
    @DisplayName("小额单 → L1：无 APPROVER 角色被拒 403，APPROVER 可通过")
    void smallAmountLevel1() {
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, LocalDate.now(),
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("5")))));
        purchaseOrderService.submit(po.id());
        assertThat(po.totalAmount()).isEqualByComparingTo("50.00");
        assertThat(pendingNode(po.id())).isEqualTo("L1");

        assertThatThrownBy(() -> runAs("plain-approver", List.of("approval:approve"),
                () -> approvalService.approve(PO, po.id(), "同意")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(403);
                    assertThat(e.getMessage()).contains("APPROVER").contains("第 1 级");
                });
        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("SUBMITTED");

        runAs("approver-l1", List.of("ROLE_APPROVER"), () -> approvalService.approve(PO, po.id(), "同意"));
        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("大额单 → L2：APPROVER 403 且待审列表不可见，SENIOR_APPROVER 可通过")
    void largeAmountLevel2RequiresSeniorApprover() {
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, LocalDate.now(),
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("20000"), new BigDecimal("10")))));
        purchaseOrderService.submit(po.id());
        assertThat(po.totalAmount()).isEqualByComparingTo("200000.00");
        assertThat(pendingNode(po.id())).isEqualTo("L2");

        // APPROVER 无权审批 L2（驳回同校验）
        assertThatThrownBy(() -> runAs("approver-l1", List.of("ROLE_APPROVER", "approval:approve"),
                () -> approvalService.reject(PO, po.id(), "不同意")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(403);
                    assertThat(e.getMessage()).contains("SENIOR_APPROVER").contains("第 2 级");
                });
        // APPROVER 在待审列表看不到 L2 单据
        assertThat(pendingIds("approver-l1", List.of("ROLE_APPROVER"))).doesNotContain(po.id());
        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("SUBMITTED");

        // SENIOR_APPROVER 可见且可审
        assertThat(pendingIds("senior-1", List.of("ROLE_SENIOR_APPROVER"))).contains(po.id());
        runAs("senior-1", List.of("ROLE_SENIOR_APPROVER", "approval:approve"),
                () -> approvalService.approve(PO, po.id(), "高级审批通过"));
        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("L2：ADMIN 豁免可直接审批，无 SENIOR_APPROVER 角色亦可")
    void largeAmountAdminExempt() {
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, LocalDate.now(),
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("20000"), new BigDecimal("10")))));
        purchaseOrderService.submit(po.id());
        assertThat(pendingNode(po.id())).isEqualTo("L2");

        runAs("admin-x", List.of("ROLE_ADMIN"), () -> approvalService.approve(PO, po.id(), "特批通过"));
        assertThat(purchaseOrderService.get(po.id()).status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("区间边界与无配置回落：99999.99=L1、100000=L2、SALES_RETURN 默认 L1/APPROVER")
    void resolveBoundariesAndFallback() {
        assertThat(approvalLevelResolver.resolve(PO, new BigDecimal("99999.99")).level()).isEqualTo(1);
        assertThat(approvalLevelResolver.resolve(PO, new BigDecimal("100000.00")).level()).isEqualTo(2);
        assertThat(approvalLevelResolver.resolve("SALES_ORDER", new BigDecimal("150000")).node()).isEqualTo("L2");

        ApprovalLevelResolver.Level fallback =
                approvalLevelResolver.resolve("SALES_RETURN", new BigDecimal("500000"));
        assertThat(fallback.level()).isEqualTo(1);
        assertThat(fallback.approverRole()).isEqualTo("APPROVER");
        assertThat(fallback.node()).isEqualTo("L1");
    }

    private String pendingNode(Long orderId) {
        return approvalRecordRepository
                .findFirstByBizTypeAndBizIdAndStatusOrderByIdDesc(PO, orderId, "PENDING")
                .orElseThrow().getNode();
    }

    /** 当前用户待审列表中的采购订单 id。 */
    private List<Long> pendingIds(String user, List<String> authorities) {
        return callAs(user, authorities, () -> approvalService.pending(1, 200)).getRecords().stream()
                .filter(r -> PO.equals(r.bizType()))
                .map(com.pharma.approval.dto.ApprovalPendingVO::bizId)
                .toList();
    }
}
