package com.pharma.sales.service;

import com.pharma.approval.entity.ApprovalRecord;
import com.pharma.approval.repository.ApprovalRecordRepository;
import com.pharma.approval.service.ApprovalLevelResolver;
import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Product;
import com.pharma.base.repository.CustomerRepository;
import com.pharma.base.repository.ProductRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.DataScope;
import com.pharma.common.DateValidator;
import com.pharma.common.PageResult;
import com.pharma.common.export.ExportPayload;
import com.pharma.common.export.ExportWriter;
import com.pharma.finance.service.ArApService;
import com.pharma.inventory.service.InventoryLedgerService;
import com.pharma.sales.dto.*;
import com.pharma.sales.entity.SalesOrder;
import com.pharma.sales.entity.SalesOrderItem;
import com.pharma.sales.repository.SalesOrderItemRepository;
import com.pharma.sales.repository.SalesOrderRepository;
import com.pharma.system.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 销售订单服务：CRUD + 提审 + 草稿编辑 + 驳回重提 + 作废。
 */
@Service
public class SalesOrderService {

    private static final String BIZ_TYPE = "SALES_ORDER";

    private final SalesOrderRepository orderRepository;
    private final SalesOrderItemRepository itemRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final InventoryLedgerService ledger;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final AuditLogService auditLogService;
    private final ArApService arApService;
    private final DateValidator dateValidator;
    private final ApprovalLevelResolver approvalLevelResolver;

    public SalesOrderService(SalesOrderRepository orderRepository,
                             SalesOrderItemRepository itemRepository,
                             CustomerRepository customerRepository,
                             ProductRepository productRepository,
                             InventoryLedgerService ledger,
                             ApprovalRecordRepository approvalRecordRepository,
                             AuditLogService auditLogService,
                             ArApService arApService,
                             DateValidator dateValidator,
                             ApprovalLevelResolver approvalLevelResolver) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.ledger = ledger;
        this.approvalRecordRepository = approvalRecordRepository;
        this.auditLogService = auditLogService;
        this.arApService = arApService;
        this.dateValidator = dateValidator;
        this.approvalLevelResolver = approvalLevelResolver;
    }

    @Transactional
    public SalesOrderVO create(SalesOrderCreateRequest req) {
        validateCustomer(req.customerId());
        dateValidator.validateBizDate(req.orderDate(), "订单日期");
        BuiltItems built = buildItems(req.items());

        SalesOrder order = new SalesOrder();
        order.setOrderNo(ledger.genNo("SO"));
        order.setCustomerId(req.customerId());
        order.setOrderDate(req.orderDate() == null ? LocalDate.now() : req.orderDate());
        order.setStatus("DRAFT");
        order.setTotalAmount(built.total());
        order.setCreatedBy(ledger.currentUser());
        order = orderRepository.save(order);

        List<SalesOrderItem> saved = new ArrayList<>();
        for (SalesOrderItem item : built.items()) {
            item.setOrderId(order.getId());
            saved.add(itemRepository.save(item));
        }
        Map<Long, String> names = productNames(saved.stream().map(SalesOrderItem::getProductId).toList());
        List<SalesOrderItemVO> vos = saved.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(order, vos, customerName(order.getCustomerId()));
    }

    /**
     * 编辑明细：DRAFT / REJECTED 可改，整单重建明细（P0-3）。
     * 驳回单改完仍为 REJECTED，需再 submit 重新进入审批。
     */
    @Transactional
    public SalesOrderVO update(Long id, SalesOrderCreateRequest req) {
        SalesOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售订单不存在: " + id));
        DataScope.requireOwnership(order.getCreatedBy());
        if (!"DRAFT".equals(order.getStatus()) && !"REJECTED".equals(order.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回订单可编辑，当前状态: " + order.getStatus());
        }
        validateCustomer(req.customerId());
        dateValidator.validateBizDate(req.orderDate(), "订单日期");
        BuiltItems built = buildItems(req.items());
        BigDecimal beforeTotal = order.getTotalAmount();
        Long beforeCustomerId = order.getCustomerId();
        LocalDate beforeOrderDate = order.getOrderDate();
        int beforeItemCount = itemRepository.findByOrderId(id).size();

        order.setCustomerId(req.customerId());
        if (req.orderDate() != null) {
            order.setOrderDate(req.orderDate());
        }
        order.setTotalAmount(built.total());
        orderRepository.save(order);

        itemRepository.deleteAll(itemRepository.findByOrderId(id));

        List<SalesOrderItem> saved = new ArrayList<>();
        for (SalesOrderItem item : built.items()) {
            item.setOrderId(order.getId());
            saved.add(itemRepository.save(item));
        }
        // P1-2 审计：改价（订单总价前后值）与明细行数
        auditLogService.log(ledger.currentUser(), "UPDATE_ORDER", BIZ_TYPE, id,
                "销售订单 " + order.getOrderNo() + " 编辑，总价 " + beforeTotal + " -> " + order.getTotalAmount(),
                AuditLogService.json("totalAmount", beforeTotal, "customerId", beforeCustomerId,
                        "orderDate", beforeOrderDate, "itemCount", beforeItemCount),
                AuditLogService.json("totalAmount", order.getTotalAmount(), "customerId", order.getCustomerId(),
                        "orderDate", order.getOrderDate(), "itemCount", saved.size()));
        Map<Long, String> names = productNames(saved.stream().map(SalesOrderItem::getProductId).toList());
        List<SalesOrderItemVO> vos = saved.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(order, vos, customerName(order.getCustomerId()));
    }

    public PageResult<SalesOrderVO> list(String keyword, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        // P1-23 数据权限：ADMIN / approval:view 看全部，其余仅看自己创建的单据
        Page<SalesOrder> result = orderRepository.search(keyword, status, DataScope.orderScopeUser(), pageable);

        List<Long> ids = result.getContent().stream().map(SalesOrder::getId).toList();
        Map<Long, List<SalesOrderItem>> itemMap = itemRepository.findByOrderIdIn(ids).stream()
                .collect(Collectors.groupingBy(SalesOrderItem::getOrderId));

        Map<Long, String> customerNames = customerNames(result.getContent().stream()
                .map(SalesOrder::getCustomerId).toList());
        Map<Long, String> productNames = productNames(itemMap.values().stream().flatMap(List::stream)
                .map(SalesOrderItem::getProductId).toList());

        List<SalesOrderVO> records = result.getContent().stream()
                .map(o -> toVO(o, itemMap.getOrDefault(o.getId(), List.of()).stream()
                                .map(i -> toItemVO(i, productNames)).toList(),
                        customerNames.get(o.getCustomerId())))
                .toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    /**
     * 导出用全量查询（上限 {@link ExportWriter#MAX_ROWS}）：复用列表的查询条件与数据权限，不分页。
     */
    public ExportPayload<SalesOrderExportRow> exportRows(String keyword, String status) {
        Pageable pageable = PageRequest.of(0, ExportWriter.MAX_ROWS, Sort.by(Sort.Direction.DESC, "id"));
        Page<SalesOrder> result = orderRepository.search(keyword, status, DataScope.orderScopeUser(), pageable);
        Map<Long, String> customerNames = customerNames(result.getContent().stream()
                .map(SalesOrder::getCustomerId).toList());
        List<SalesOrderExportRow> rows = result.getContent().stream()
                .map(o -> new SalesOrderExportRow(o.getOrderNo(), billDate(o.getOrderDate()),
                        customerNames.get(o.getCustomerId()), o.getTotalAmount(), o.getStatus(), o.getCreatedBy()))
                .toList();
        return new ExportPayload<>(rows, result.getTotalElements(), ExportWriter.MAX_ROWS);
    }

    private static String billDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    public SalesOrderVO get(Long id) {
        SalesOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售订单不存在: " + id));
        DataScope.requireOwnership(order.getCreatedBy());
        List<SalesOrderItem> rawItems = itemRepository.findByOrderId(id);
        Map<Long, String> names = productNames(rawItems.stream().map(SalesOrderItem::getProductId).toList());
        List<SalesOrderItemVO> items = rawItems.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(order, items, customerName(order.getCustomerId()));
    }

    @Transactional
    public SalesOrderVO submit(Long id) {
        SalesOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售订单不存在: " + id));
        DataScope.requireOwnership(order.getCreatedBy());
        if (!"DRAFT".equals(order.getStatus()) && !"REJECTED".equals(order.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回订单可提交，当前状态: " + order.getStatus());
        }
        checkCreditLimit(order);
        order.setStatus("SUBMITTED");
        orderRepository.save(order);

        writePendingApproval(order);

        auditLogService.log(ledger.currentUser(), "SUBMIT_ORDER", BIZ_TYPE, order.getId(),
                "提交销售订单 " + order.getOrderNo() + "，金额 " + order.getTotalAmount().toPlainString(),
                AuditLogService.json("orderNo", order.getOrderNo(), "status", "DRAFT",
                        "totalAmount", order.getTotalAmount()),
                AuditLogService.json("orderNo", order.getOrderNo(), "status", "SUBMITTED",
                        "totalAmount", order.getTotalAmount()));

        List<SalesOrderItem> rawItems = itemRepository.findByOrderId(id);
        Map<Long, String> names = productNames(rawItems.stream().map(SalesOrderItem::getProductId).toList());
        List<SalesOrderItemVO> items = rawItems.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(order, items, customerName(order.getCustomerId()));
    }

    /**
     * 驳回后重新提交：REJECTED -> DRAFT -> SUBMITTED，并写新的 PENDING 审批记录（P0-3）。
     */
    @Transactional
    public SalesOrderVO resubmit(Long id) {
        SalesOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售订单不存在: " + id));
        DataScope.requireOwnership(order.getCreatedBy());
        if (!"REJECTED".equals(order.getStatus())) {
            throw BusinessException.badRequest("仅已驳回订单可重新提交，当前状态: " + order.getStatus());
        }
        order.setStatus("DRAFT");
        orderRepository.save(order);
        return submit(id);
    }

    /**
     * 作废：DRAFT / SUBMITTED -> VOID（终态），已提交单据同时终止待审批记录（P0-3）。
     * reason 写入审计日志留痕。
     */
    @Transactional
    public SalesOrderVO voidOrder(Long id, String reason) {
        SalesOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售订单不存在: " + id));
        DataScope.requireOwnership(order.getCreatedBy());
        if (!"DRAFT".equals(order.getStatus()) && !"SUBMITTED".equals(order.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已提交订单可作废，当前状态: " + order.getStatus());
        }
        if ("SUBMITTED".equals(order.getStatus())) {
            cancelPendingApproval(id);
        }
        String beforeStatus = order.getStatus();
        order.setStatus("VOID");
        orderRepository.save(order);

        auditLogService.log(ledger.currentUser(), "VOID_ORDER", BIZ_TYPE, order.getId(),
                "作废销售订单 " + order.getOrderNo() + (reason == null || reason.isBlank()
                        ? "" : "，原因：" + reason.trim()),
                AuditLogService.json("orderNo", order.getOrderNo(), "status", beforeStatus,
                        "totalAmount", order.getTotalAmount()),
                AuditLogService.json("orderNo", order.getOrderNo(), "status", "VOID",
                        "totalAmount", order.getTotalAmount(), "reason", reason));

        List<SalesOrderItem> rawItems = itemRepository.findByOrderId(id);
        Map<Long, String> names = productNames(rawItems.stream().map(SalesOrderItem::getProductId).toList());
        List<SalesOrderItemVO> items = rawItems.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(order, items, customerName(order.getCustomerId()));
    }

    /**
     * 信用拦截（P0-5）：提审时「未结应收 + 本单金额 > creditLimit」→ 400。
     * creditLimit 为空视为不限；特批由审批人强制通过覆盖（审批动作不再重复校验）。
     */
    private void checkCreditLimit(SalesOrder order) {
        Customer customer = customerRepository.findById(order.getCustomerId()).orElse(null);
        if (customer == null || customer.getCreditLimit() == null) {
            return;
        }
        BigDecimal outstanding = arApService.outstanding(order.getCustomerId());
        BigDecimal exposure = outstanding.add(order.getTotalAmount());
        if (exposure.compareTo(customer.getCreditLimit()) > 0) {
            throw BusinessException.badRequest("超出信用额度：未结应收 " + outstanding.toPlainString()
                    + " + 本单 " + order.getTotalAmount().toPlainString()
                    + " > 信用额度 " + customer.getCreditLimit().toPlainString()
                    + "（客户 " + customer.getName() + "）");
        }
    }

    private void cancelPendingApproval(Long orderId) {
        for (ApprovalRecord r : approvalRecordRepository.findByBizTypeAndBizIdAndStatus(BIZ_TYPE, orderId, "PENDING")) {
            r.setStatus("CANCELLED");
            approvalRecordRepository.save(r);
        }
    }

    private void writePendingApproval(SalesOrder order) {
        ApprovalRecord r = new ApprovalRecord();
        r.setBizType(BIZ_TYPE);
        r.setBizId(order.getId());
        // 制单人语义由订单 created_by 承载，审批人由实际审批动作写入
        r.setApprover(null);
        // P1-1 金额分级：待审节点按总金额解析（L1/L2）
        r.setNode(approvalLevelResolver.node(BIZ_TYPE, order.getTotalAmount()));
        r.setStatus("PENDING");
        approvalRecordRepository.save(r);
    }

    private Customer validateCustomer(Long customerId) {
        if (customerId == null) {
            throw BusinessException.badRequest("客户不能为空");
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> BusinessException.badRequest("客户不存在: " + customerId));
        // R3 首营阻断：客户未过首营不可开单
        if (!"APPROVED".equals(customer.getFirstAuditStatus())) {
            throw BusinessException.badRequest("客户未过首营审核，不可开销售单: " + customer.getName());
        }
        // R4 证照效期锁交易：证照已过期不可开单
        if (customer.getLicenseExpire() != null && customer.getLicenseExpire().isBefore(LocalDate.now())) {
            throw BusinessException.badRequest("客户证照已过期，不可开销售单: " + customer.getName());
        }
        return customer;
    }

    private record BuiltItems(List<SalesOrderItem> items, BigDecimal total) {
    }

    private BuiltItems buildItems(List<SalesOrderItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("订单明细不能为空");
        }
        Set<Long> seen = new HashSet<>();
        List<SalesOrderItem> toSave = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (SalesOrderItemRequest it : items) {
            if (it.productId() == null) {
                throw BusinessException.badRequest("商品不能为空");
            }
            Product product = productRepository.findById(it.productId())
                    .orElseThrow(() -> BusinessException.badRequest("商品不存在: " + it.productId()));
            // R3 首营阻断：商品未过首营不可开单
            if (!"APPROVED".equals(product.getFirstAuditStatus())) {
                throw BusinessException.badRequest("商品未过首营审核，不可开销售单: " + product.getGenericName());
            }
            if (!seen.add(it.productId())) {
                throw BusinessException.badRequest("明细商品重复: " + it.productId());
            }
            BigDecimal qty = it.quantity() == null ? BigDecimal.ZERO : it.quantity();
            BigDecimal price = it.unitPrice() == null ? BigDecimal.ZERO : it.unitPrice();
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("商品数量必须大于0");
            }
            if (price.compareTo(BigDecimal.ZERO) < 0) {
                throw BusinessException.badRequest("单价不能为负");
            }
            BigDecimal amount = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            SalesOrderItem item = new SalesOrderItem();
            item.setProductId(it.productId());
            item.setQuantity(qty);
            item.setUnitPrice(price);
            item.setAmount(amount);
            toSave.add(item);
            total = total.add(amount);
        }
        return new BuiltItems(toSave, total);
    }

    private SalesOrderItemVO toItemVO(SalesOrderItem item, Map<Long, String> productNames) {
        return new SalesOrderItemVO(item.getId(), item.getProductId(),
                productNames.get(item.getProductId()), item.getQuantity(),
                item.getUnitPrice(), item.getAmount());
    }

    private SalesOrderVO toVO(SalesOrder order, List<SalesOrderItemVO> items, String customerName) {
        return new SalesOrderVO(order.getId(), order.getOrderNo(), order.getCustomerId(),
                customerName, order.getOrderDate(), order.getStatus(), order.getTotalAmount(),
                order.getCreatedBy(), order.getCreatedAt(), items);
    }

    /** 客户名称（单条）。 */
    private String customerName(Long customerId) {
        return customerId == null ? null
                : customerRepository.findById(customerId).map(Customer::getName).orElse(null);
    }

    /** 批量取客户名称，避免列表 N+1（P1-30）。 */
    private Map<Long, String> customerNames(List<Long> customerIds) {
        Set<Long> ids = customerIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return customerRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getName));
    }

    /** 批量取商品名称，避免列表 N+1（P1-30）。 */
    private Map<Long, String> productNames(List<Long> productIds) {
        Set<Long> ids = productIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, p -> p.getGenericName() == null ? "" : p.getGenericName()));
    }
}
