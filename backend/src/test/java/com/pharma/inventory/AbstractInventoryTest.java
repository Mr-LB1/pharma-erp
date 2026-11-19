package com.pharma.inventory;

import com.pharma.approval.repository.ApprovalRecordRepository;
import com.pharma.approval.service.ApprovalService;
import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Product;
import com.pharma.base.entity.Supplier;
import com.pharma.base.entity.Warehouse;
import com.pharma.base.repository.CustomerRepository;
import com.pharma.base.repository.ProductRepository;
import com.pharma.base.repository.SupplierRepository;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.repository.CostLedgerRepository;
import com.pharma.inventory.repository.StockRepository;
import com.pharma.inventory.service.InventoryLedgerService;
import com.pharma.purchase.service.PurchaseOrderService;
import com.pharma.purchase.service.PurchaseReceiveService;
import com.pharma.purchase.service.PurchaseReturnService;
import com.pharma.inventory.service.StockTransferService;
import com.pharma.purchase.service.PurchaseReturnService;
import com.pharma.inventory.service.StockTransferService;
import com.pharma.sales.service.SalesDeliveryService;
import com.pharma.sales.service.SalesOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 库存规则测试基类：真实 PG + 事务回滚，隔离测试数据。
 */
@SpringBootTest
@Transactional
public abstract class AbstractInventoryTest {

    @Autowired
    protected StockRepository stockRepository;
    @Autowired
    protected CostLedgerRepository costLedgerRepository;
    @Autowired
    protected InventoryLedgerService ledger;
    @Autowired
    protected WarehouseRepository warehouseRepository;
    @Autowired
    protected ProductRepository productRepository;
    @Autowired
    protected SupplierRepository supplierRepository;
    @Autowired
    protected CustomerRepository customerRepository;
    @Autowired
    protected PurchaseOrderService purchaseOrderService;
    @Autowired
    protected PurchaseReceiveService purchaseReceiveService;
    @Autowired
    protected SalesOrderService salesOrderService;
    @Autowired
    protected SalesDeliveryService salesDeliveryService;
    @Autowired
    protected ApprovalService approvalService;
    @Autowired
    protected ApprovalRecordRepository approvalRecordRepository;
    @Autowired
    protected PurchaseReturnService purchaseReturnService;
    @Autowired
    protected StockTransferService stockTransferService;

    protected Long warehouseId;
    protected Long productId;
    protected Long supplierId;
    protected Long customerId;

    @BeforeEach
    void setUpFixtures() {
        Warehouse w = new Warehouse();
        w.setName("测试仓");
        w.setTempType("ROOM_TEMP");
        w.setStatus("ACTIVE");
        warehouseId = warehouseRepository.save(w).getId();

        Product p = new Product();
        p.setCode("P-" + System.nanoTime());
        p.setGenericName("测试药品");
        p.setFirstAuditStatus("APPROVED");
        p.setStatus("ACTIVE");
        productId = productRepository.save(p).getId();

        Supplier s = new Supplier();
        s.setName("测试供应商");
        s.setFirstAuditStatus("APPROVED");
        s.setStatus("ACTIVE");
        supplierId = supplierRepository.save(s).getId();

        Customer c = new Customer();
        c.setName("测试客户");
        c.setFirstAuditStatus("APPROVED");
        c.setStatus("ACTIVE");
        customerId = customerRepository.save(c).getId();
    }

    protected Stock createStock(String batchNo, LocalDate expireDate, BigDecimal qty, BigDecimal cost) {
        Stock st = new Stock();
        st.setProductId(productId);
        st.setBatchNo(batchNo);
        st.setExpireDate(expireDate);
        st.setLocationId(warehouseId);
        st.setQuantity(qty);
        st.setUnitCost(cost);
        return stockRepository.save(st);
    }

    /**
     * 以指定用户身份执行：审批回避（审批人 ≠ 制单人）与制单人语义需要可切换当前用户。
     */
    protected void runAs(String username, Runnable action) {
        callAs(username, List.of(), () -> {
            action.run();
            return null;
        });
    }

    /** 以指定用户 + 权限码执行（用于数据权限/成本脱敏测试）。 */
    protected void runAs(String username, List<String> authorities, Runnable action) {
        callAs(username, authorities, () -> {
            action.run();
            return null;
        });
    }

    /** 以指定用户 + 权限码执行并返回结果。 */
    protected <T> T callAs(String username, List<String> authorities, java.util.function.Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            List<org.springframework.security.core.GrantedAuthority> gas = authorities.stream()
                    .map(a -> (org.springframework.security.core.GrantedAuthority)
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(a))
                    .toList();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", gas));
            SecurityContextHolder.setContext(context);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /** 以第三方审批人身份审核通过（回避自审，且需具备 P1-1 分级审批所需的 APPROVER 角色）。 */
    protected void approveAsApprover(String bizType, Long bizId) {
        runAs("approver", List.of("ROLE_APPROVER"), () -> approvalService.approve(bizType, bizId, "审批通过"));
    }

    /** 以第三方审批人身份驳回（驳回必须留意见）。 */
    protected void rejectAsApprover(String bizType, Long bizId, String comment) {
        runAs("approver", List.of("ROLE_APPROVER"), () -> approvalService.reject(bizType, bizId, comment));
    }

    /**
     * 采购入库：建单 → 提交 → 审批（PUSHED）→ WMS 按单据量回传（COMPLETED，落库存）。
     * 批次十四：create 只记草稿，审批只下发 WMS，回传后才动库存。
     */
    protected com.pharma.purchase.dto.PurchaseReceiveVO receiveApproved(
            com.pharma.purchase.dto.PurchaseReceiveCreateRequest req) {
        com.pharma.purchase.dto.PurchaseReceiveVO created = purchaseReceiveService.create(req);
        purchaseReceiveService.submit(created.id());
        approveAsApprover("PURCHASE_RECEIVE", created.id());
        purchaseReceiveService.wmsCallback(created.id(), null);
        return purchaseReceiveService.get(created.id());
    }

    /** 销售出库：建单 → 提交 → 审批（PUSHED）→ WMS 按单据量回传（COMPLETED，FEFO 出库）。 */
    protected com.pharma.sales.dto.SalesDeliveryVO deliveryApproved(
            com.pharma.sales.dto.SalesDeliveryCreateRequest req) {
        com.pharma.sales.dto.SalesDeliveryVO created = salesDeliveryService.create(req);
        salesDeliveryService.submit(created.id());
        approveAsApprover("SALES_DELIVERY", created.id());
        salesDeliveryService.wmsCallback(created.id(), null);
        return salesDeliveryService.get(created.id());
    }

    /** 采购退货 / 仓库调拨：审批通过后再按单据量完成 WMS 回传（落库存与财务）。 */
    protected void wmsCallbackFull(String bizType, Long id) {
        switch (bizType) {
            case "PURCHASE_RETURN" -> purchaseReturnService.wmsCallback(id, null);
            case "STOCK_TRANSFER" -> stockTransferService.wmsCallback(id, null);
            default -> throw new IllegalArgumentException("不支持的类型: " + bizType);
        }
    }
}
