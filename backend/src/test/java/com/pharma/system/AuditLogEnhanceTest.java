package com.pharma.system;

import com.pharma.compliance.AbstractComplianceTest;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.reports.controller.ReportController;
import com.pharma.system.entity.AuditLog;
import com.pharma.system.entity.SysRole;
import com.pharma.system.repository.AuditLogRepository;
import com.pharma.system.repository.SysRoleRepository;
import com.pharma.system.service.AuditLogService;
import com.pharma.system.service.RoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-2 审计增强：关键操作前后值快照 + 来源 IP + 导出留痕。
 */
class AuditLogEnhanceTest extends AbstractComplianceTest {

    @Autowired
    private AuditLogService auditLogService;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private RoleService roleService;
    @Autowired
    private SysRoleRepository sysRoleRepository;
    @Autowired
    private ReportController reportController;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("权限变更审计写入前后值 diff")
    void rolePermissionUpdateWritesBeforeAfterDiff() {
        SysRole role = new SysRole();
        role.setCode("TEST_ROLE_" + System.nanoTime());
        role.setName("测试角色");
        Long roleId = sysRoleRepository.save(role).getId();

        roleService.updatePermissions(roleId, List.of("product:view"));
        roleService.updatePermissions(roleId, List.of("product:view", "report:view"));

        List<AuditLog> logs = auditLogRepository
                .findByActionAndObjectTypeAndObjectIdOrderByIdDesc("ROLE_PERMISSION_UPDATE", "SYS_ROLE", roleId);
        assertFalse(logs.isEmpty(), "权限变更应写审计");
        AuditLog latest = logs.get(0);
        assertTrue(latest.getBeforeValue().contains("product:view"), "before 应含原权限码");
        assertFalse(latest.getBeforeValue().contains("report:view"), "before 不应含新增权限码");
        assertTrue(latest.getAfterValue().contains("report:view"), "after 应含新增权限码");
        assertTrue(latest.getAfterValue().contains("added"), "after 应含新增集合");
        assertTrue(latest.getAfterValue().contains("removed"), "after 应含移除集合");
    }

    @Test
    @DisplayName("采购订单改价写入总价前后值")
    void orderPriceChangeWritesBeforeAfter() {
        Long orderId = callAs("buyer", List.of(), () ->
                purchaseOrderService.create(purchaseRequest(new BigDecimal("10.00"))).id());
        callAs("buyer", List.of(), () -> {
            purchaseOrderService.update(orderId, purchaseRequest(new BigDecimal("15.00")));
            return null;
        });

        List<AuditLog> logs = auditLogRepository
                .findByActionAndObjectTypeAndObjectIdOrderByIdDesc("UPDATE_ORDER", "PURCHASE_ORDER", orderId);
        assertFalse(logs.isEmpty(), "改价应写审计");
        AuditLog latest = logs.get(0);
        assertTrue(latest.getBeforeValue().contains("\"totalAmount\":20.00"), latest.getBeforeValue());
        assertTrue(latest.getAfterValue().contains("\"totalAmount\":30.00"), latest.getAfterValue());
    }

    @Test
    @DisplayName("审批审计记录单据状态 SUBMITTED -> APPROVED")
    void approvalWritesStatusBeforeAfter() {
        Long orderId = callAs("buyer", List.of(), () ->
                purchaseOrderService.create(purchaseRequest(new BigDecimal("12.50"))).id());
        callAs("buyer", List.of(), () -> purchaseOrderService.submit(orderId));
        runAs("approver", List.of("ROLE_APPROVER"),
                () -> approvalService.approve("PURCHASE_ORDER", orderId, "审批通过"));

        List<AuditLog> logs = auditLogRepository
                .findByActionAndObjectTypeAndObjectIdOrderByIdDesc("ORDER_APPROVED", "PURCHASE_ORDER", orderId);
        assertFalse(logs.isEmpty(), "审批应写审计");
        AuditLog latest = logs.get(0);
        assertTrue(latest.getBeforeValue().contains("\"status\":\"SUBMITTED\""), latest.getBeforeValue());
        assertTrue(latest.getAfterValue().contains("\"status\":\"APPROVED\""), latest.getAfterValue());
    }

    @Test
    @DisplayName("近效期导出写 EXPORT 审计并记录 X-Forwarded-For 首段 IP")
    void exportWritesAuditWithForwardedIp() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        MockHttpServletResponse response = new MockHttpServletResponse();
        callAs("exporter", List.of("report:view"), () -> {
            try {
                reportController.exportNearExpiry(30, response);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        AuditLog exportLog = auditLogRepository.findAll().stream()
                .filter(a -> "EXPORT".equals(a.getAction()) && "exporter".equals(a.getUsername()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("导出应写 EXPORT 审计"));
        assertEquals("NEAR_EXPIRY", exportLog.getObjectType());
        assertEquals("203.0.113.9", exportLog.getIp(), "应取 X-Forwarded-For 首段");
        assertTrue(exportLog.getDetail().contains("days=30"), exportLog.getDetail());
        assertTrue(exportLog.getAfterValue().contains("\"rows\":"), exportLog.getAfterValue());
        assertTrue(response.getContentAsByteArray().length > 0, "导出文件不应为空");
    }

    @Test
    @DisplayName("非 Web 线程写审计不报错，IP 为空")
    void nonWebThreadWritesNullIp() {
        RequestContextHolder.resetRequestAttributes();
        auditLogService.log("system", "TEST_NO_WEB_CONTEXT", "TEST", null, "后台任务审计");

        AuditLog saved = auditLogRepository.findAll().stream()
                .filter(a -> "TEST_NO_WEB_CONTEXT".equals(a.getAction()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("审计应写入成功"));
        assertNull(saved.getIp());
        assertNull(saved.getBeforeValue());
        assertNull(saved.getAfterValue());
    }

    private PurchaseOrderCreateRequest purchaseRequest(BigDecimal unitPrice) {
        return new PurchaseOrderCreateRequest(supplierId, LocalDate.now(),
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("2"), unitPrice)));
    }
}
