package com.pharma.compliance;

import com.pharma.approval.dto.ApprovalPendingVO;
import com.pharma.common.PageResult;
import com.pharma.sales.entity.SalesOrder;
import com.pharma.sales.repository.SalesOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次十（决策 5）：待审列表按审批人角色过滤。
 * 单据金额命中 approval_config 的层级角色 → 只有持有该角色的用户能在待审列表看到；
 * ADMIN 看全部；无角色用户看不到任何待审单。
 */
class ApprovalPendingRoleFilterTest extends AbstractComplianceTest {

    @Autowired
    private SalesOrderRepository salesOrderRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private SalesOrder newSubmittedOrder(String no, String amount) {
        SalesOrder o = new SalesOrder();
        o.setOrderNo(no);
        o.setCustomerId(customerId);
        o.setOrderDate(LocalDate.now());
        o.setStatus("SUBMITTED");
        o.setTotalAmount(new BigDecimal(amount));
        o.setCreatedBy("maker");
        return salesOrderRepository.save(o);
    }

    @Test
    void L2单据仅对应层级角色可见_低层级列表看不到() {
        // 将 L2 角色改为独立角色，便于验证层级差异（事务内修改，测试回滚）
        jdbc.update("""
                UPDATE approval_config SET approver_role = 'SENIOR_APPROVER'
                WHERE biz_type = 'SALES_ORDER' AND level = 2
                """);

        SalesOrder l1 = newSubmittedOrder("RF-L1-" + System.nanoTime(), "100");
        SalesOrder l2 = newSubmittedOrder("RF-L2-" + System.nanoTime(), "200000");

        // 低层级审批人（APPROVER）：只看得到 L1
        List<Long> approverIds = ids(callAs("approver-1", List.of("approval:view", "ROLE_APPROVER"),
                () -> approvalService.pending(1, 200)));
        assertThat(approverIds).contains(l1.getId()).doesNotContain(l2.getId());

        // 高层级审批人（SENIOR_APPROVER）：只看得到 L2
        List<Long> seniorIds = ids(callAs("approver-2", List.of("approval:view", "ROLE_SENIOR_APPROVER"),
                () -> approvalService.pending(1, 200)));
        assertThat(seniorIds).contains(l2.getId()).doesNotContain(l1.getId());

        // ADMIN：全部可见
        List<Long> adminIds = ids(callAs("admin-1", List.of("ROLE_ADMIN"),
                () -> approvalService.pending(1, 200)));
        assertThat(adminIds).contains(l1.getId(), l2.getId());

        // 无任何角色：看不到待审单
        PageResult<ApprovalPendingVO> none = callAs("nobody", List.of("approval:view"),
                () -> approvalService.pending(1, 200));
        assertThat(none.getTotal()).isZero();
        assertThat(none.getRecords()).isEmpty();
    }

    @Test
    void 角色过滤与计数一致且分页正确() {
        jdbc.update("""
                UPDATE approval_config SET approver_role = 'SENIOR_APPROVER'
                WHERE biz_type = 'SALES_ORDER' AND level = 2
                """);
        for (int i = 0; i < 3; i++) {
            newSubmittedOrder("RF-PG-" + i + "-" + System.nanoTime(), "200000");
        }
        PageResult<ApprovalPendingVO> page = callAs("approver-2", List.of("ROLE_SENIOR_APPROVER"),
                () -> approvalService.pending(1, 2));
        // 计数与当页条数一致；当页均为 L2 大额单
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(3);
        assertThat(page.getRecords()).hasSize(2);
        assertThat(page.getRecords()).allSatisfy(r ->
                assertThat(r.totalAmount()).isGreaterThan(new BigDecimal("100000")));
    }

    private List<Long> ids(PageResult<ApprovalPendingVO> page) {
        return page.getRecords().stream().map(ApprovalPendingVO::bizId).toList();
    }
}
