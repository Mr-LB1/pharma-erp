package com.pharma.purchase.service;

import com.pharma.approval.entity.ApprovalRecord;
import com.pharma.approval.repository.ApprovalRecordRepository;
import com.pharma.approval.service.ApprovalLevelResolver;
import com.pharma.base.entity.Product;
import com.pharma.base.entity.Supplier;
import com.pharma.base.repository.ProductRepository;
import com.pharma.base.repository.SupplierRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.DataScope;
import com.pharma.common.DateValidator;
import com.pharma.common.PageResult;
import com.pharma.common.export.ExportPayload;
import com.pharma.common.export.ExportWriter;
import com.pharma.inventory.service.InventoryLedgerService;
import com.pharma.purchase.dto.*;
import com.pharma.purchase.entity.PurchaseOrder;
import com.pharma.purchase.entity.PurchaseOrderItem;
import com.pharma.purchase.repository.PurchaseOrderItemRepository;
import com.pharma.purchase.repository.PurchaseOrderRepository;
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
 * 采购订单服务：CRUD + 提审 + 草稿编辑 + 驳回重提 + 作废。
 */
@Service
public class PurchaseOrderService {

    private static final String BIZ_TYPE = "PURCHASE_ORDER";

    private final PurchaseOrderRepository orderRepository;
    private final PurchaseOrderItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final InventoryLedgerService ledger;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final AuditLogService auditLogService;
    private final DateValidator dateValidator;
    private final ApprovalLevelResolver approvalLevelResolver;

    public PurchaseOrderService(PurchaseOrderRepository orderRepository,
                                PurchaseOrderItemRepository itemRepository,
                                SupplierRepository supplierRepository,
                                ProductRepository productRepository,
                                InventoryLedgerService ledger,
                                ApprovalRecordRepository approvalRecordRepository,
                                AuditLogService auditLogService,
                                DateValidator dateValidator,
                                ApprovalLevelResolver approvalLevelResolver) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.ledger = ledger;
        this.approvalRecordRepository = approvalRecordRepository;
        this.auditLogService = auditLogService;
        this.dateValidator = dateValidator;
        this.approvalLevelResolver = approvalLevelResolver;
    }

    @Transactional
    public PurchaseOrderVO create(PurchaseOrderCreateRequest req) {
        validateSupplier(req.supplierId());
        dateValidator.validateBizDate(req.orderDate(), "订单日期");
        BuiltItems built = buildItems(req.items());

        PurchaseOrder order = new PurchaseOrder();
        order.setOrderNo(ledger.genNo("PO"));
        order.setSupplierId(req.supplierId());
        order.setOrderDate(req.orderDate() == null ? LocalDate.now() : req.orderDate());
        order.setStatus("DRAFT");
        order.setTotalAmount(built.total());
        order.setCreatedBy(ledger.currentUser());
        order = orderRepository.save(order);

        List<PurchaseOrderItem> saved = new ArrayList<>();
        for (PurchaseOrderItem item : built.items()) {
            item.setOrderId(order.getId());
            saved.add(itemRepository.save(item));
        }
        Map<Long, String> names = productNames(saved.stream().map(PurchaseOrderItem::getProductId).toList());
        List<PurchaseOrderItemVO> vos = saved.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(order, vos, supplierName(order.getSupplierId()));
    }

    /**
     * 编辑明细：DRAFT / REJECTED 可改，整单重建明细（P0-3）。
     * 驳回单改完仍为 REJECTED，需再 submit 重新进入审批。
     */
    @Transactional
    public PurchaseOrderVO update(Long id, PurchaseOrderCreateRequest req) {
        PurchaseOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("采购订单不存在: " + id));
        DataScope.requireOwnership(order.getCreatedBy());
        if (!"DRAFT".equals(order.getStatus()) && !"REJECTED".equals(order.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回订单可编辑，当前状态: " + order.getStatus());
        }
        validateSupplier(req.supplierId());
        dateValidator.validateBizDate(req.orderDate(), "订单日期");
        BuiltItems built = buildItems(req.items());
        BigDecimal beforeTotal = order.getTotalAmount();
        Long beforeSupplierId = order.getSupplierId();
        LocalDate beforeOrderDate = order.getOrderDate();
        int beforeItemCount = itemRepository.findByOrderId(id).size();

        order.setSupplierId(req.supplierId());
        if (req.orderDate() != null) {
            order.setOrderDate(req.orderDate());
        }
        order.setTotalAmount(built.total());
        orderRepository.save(order);

        itemRepository.deleteAll(itemRepository.findByOrderId(id));

        List<PurchaseOrderItem> saved = new ArrayList<>();
        for (PurchaseOrderItem item : built.items()) {
            item.setOrderId(order.getId());
            saved.add(itemRepository.save(item));
        }
        // P1-2 审计：改价（订单总价前后值）与明细行数
        auditLogService.log(ledger.currentUser(), "UPDATE_ORDER", BIZ_TYPE, id,
                "采购订单 " + order.getOrderNo() + " 编辑，总价 " + beforeTotal + " -> " + order.getTotalAmount(),
                AuditLogService.json("totalAmount", beforeTotal, "supplierId", beforeSupplierId,
                        "orderDate", beforeOrderDate, "itemCount", beforeItemCount),
                AuditLogService.json("totalAmount", order.getTotalAmount(), "supplierId", order.getSupplierId(),
                        "orderDate", order.getOrderDate(), "itemCount", saved.size()));
        Map<Long, String> names = productNames(saved.stream().map(PurchaseOrderItem::getProductId).toList());
        List<PurchaseOrderItemVO> vos = saved.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(order, vos, supplierName(order.getSupplierId()));
    }

    public PageResult<PurchaseOrderVO> list(String keyword, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        // P1-23 数据权限：ADMIN / approval:view 看全部，其余仅看自己创建的单据
        Page<PurchaseOrder> result = orderRepository.search(keyword, status, DataScope.orderScopeUser(), pageable);

        List<Long> ids = result.getContent().stream().map(PurchaseOrder::getId).toList();
        Map<Long, List<PurchaseOrderItem>> itemMap = itemRepository.findByOrderIdIn(ids).stream()
                .collect(Collectors.groupingBy(PurchaseOrderItem::getOrderId));

        Map<Long, String> supplierNames = supplierNames(result.getContent().stream()
                .map(PurchaseOrder::getSupplierId).toList());
        Map<Long, String> productNames = productNames(itemMap.values().stream().flatMap(List::stream)
                .map(PurchaseOrderItem::getProductId).toList());

        List<PurchaseOrderVO> records = result.getContent().stream()
                .map(o -> toVO(o, itemMap.getOrDefault(o.getId(), List.of()).stream()
                                .map(i -> toItemVO(i, productNames)).toList(),
                        supplierNames.get(o.getSupplierId())))
                .toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    /**
     * 导出用全量查询（上限 {@link ExportWriter#MAX_ROWS}）：复用列表的查询条件与数据权限，不分页。
     */
    public ExportPayload<PurchaseOrderExportRow> exportRows(String keyword, String status) {
        Pageable pageable = PageRequest.of(0, ExportWriter.MAX_ROWS, Sort.by(Sort.Direction.DESC, "id"));
        Page<PurchaseOrder> result = orderRepository.search(keyword, status, DataScope.orderScopeUser(), pageable);
        Map<Long, String> supplierNames = supplierNames(result.getContent().stream()
                .map(PurchaseOrder::getSupplierId).toList());
        List<PurchaseOrderExportRow> rows = result.getContent().stream()
                .map(o -> new PurchaseOrderExportRow(o.getOrderNo(), billDate(o.getOrderDate()),
                        supplierNames.get(o.getSupplierId()), o.getTotalAmount(), o.getStatus(), o.getCreatedBy()))
                .toList();
        return new ExportPayload<>(rows, result.getTotalElements(), ExportWriter.MAX_ROWS);
    }

    private static String billDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    public PurchaseOrderVO get(Long id) {
        PurchaseOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("采购订单不存在: " + id));
        DataScope.requireOwnership(order.getCreatedBy());
        List<PurchaseOrderItem> rawItems = itemRepository.findByOrderId(id);
        Map<Long, String> names = productNames(rawItems.stream().map(PurchaseOrderItem::getProductId).toList());
        List<PurchaseOrderItemVO> items = rawItems.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(order, items, supplierName(order.getSupplierId()));
    }

    @Transactional
    public PurchaseOrderVO submit(Long id) {
        PurchaseOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("采购订单不存在: " + id));
        DataScope.requireOwnership(order.getCreatedBy());
        if (!"DRAFT".equals(order.getStatus()) && !"REJECTED".equals(order.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回订单可提交，当前状态: " + order.getStatus());
        }
        order.setStatus("SUBMITTED");
        orderRepository.save(order);

        writePendingApproval(order);

        auditLogService.log(ledger.currentUser(), "SUBMIT_ORDER", BIZ_TYPE, order.getId(),
                "提交采购订单 " + order.getOrderNo() + "，金额 " + order.getTotalAmount().toPlainString(),
                AuditLogService.json("orderNo", order.getOrderNo(), "status", "DRAFT",
                        "totalAmount", order.getTotalAmount()),
                AuditLogService.json("orderNo", order.getOrderNo(), "status", "SUBMITTED",
                        "totalAmount", order.getTotalAmount()));

        List<PurchaseOrderItem> rawItems = itemRepository.findByOrderId(id);
        Map<Long, String> names = productNames(rawItems.stream().map(PurchaseOrderItem::getProductId).toList());
        List<PurchaseOrderItemVO> items = rawItems.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(order, items, supplierName(order.getSupplierId()));
    }

    /**
     * 驳回后重新提交：REJECTED -> DRAFT -> SUBMITTED，并写新的 PENDING 审批记录（P0-3）。
     */
    @Transactional
    public PurchaseOrderVO resubmit(Long id) {
        PurchaseOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("采购订单不存在: " + id));
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
    public PurchaseOrderVO voidOrder(Long id, String reason) {
        PurchaseOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("采购订单不存在: " + id));
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
                "作废采购订单 " + order.getOrderNo() + (reason == null || reason.isBlank()
                        ? "" : "，原因：" + reason.trim()),
                AuditLogService.json("orderNo", order.getOrderNo(), "status", beforeStatus,
                        "totalAmount", order.getTotalAmount()),
                AuditLogService.json("orderNo", order.getOrderNo(), "status", "VOID",
                        "totalAmount", order.getTotalAmount(), "reason", reason));

        List<PurchaseOrderItem> rawItems = itemRepository.findByOrderId(id);
        Map<Long, String> names = productNames(rawItems.stream().map(PurchaseOrderItem::getProductId).toList());
        List<PurchaseOrderItemVO> items = rawItems.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(order, items, supplierName(order.getSupplierId()));
    }

    private void cancelPendingApproval(Long orderId) {
        for (ApprovalRecord r : approvalRecordRepository.findByBizTypeAndBizIdAndStatus(BIZ_TYPE, orderId, "PENDING")) {
            r.setStatus("CANCELLED");
            approvalRecordRepository.save(r);
        }
    }

    private void writePendingApproval(PurchaseOrder order) {
        ApprovalRecord r = new ApprovalRecord();
        r.setBizType(BIZ_TYPE);
        r.setBizId(order.getId());
        // 制单人语义由订单 created_by 承载，审批人由实际审批动作写入（避免把提交人误记为审批人）
        r.setApprover(null);
        // P1-1 金额分级：待审节点按总金额解析（L1/L2）
        r.setNode(approvalLevelResolver.node(BIZ_TYPE, order.getTotalAmount()));
        r.setStatus("PENDING");
        approvalRecordRepository.save(r);
    }

    private Supplier validateSupplier(Long supplierId) {
        if (supplierId == null) {
            throw BusinessException.badRequest("供应商不能为空");
        }
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> BusinessException.badRequest("供应商不存在: " + supplierId));
        // R3 首营阻断：供应商未过首营不可开单
        if (!"APPROVED".equals(supplier.getFirstAuditStatus())) {
            throw BusinessException.badRequest("供应商未过首营审核，不可开采购单: " + supplier.getName());
        }
        // R4 证照效期锁交易：证照已过期不可开单
        if (supplier.getLicenseExpire() != null && supplier.getLicenseExpire().isBefore(LocalDate.now())) {
            throw BusinessException.badRequest("供应商证照已过期，不可开采购单: " + supplier.getName());
        }
        return supplier;
    }

    private record BuiltItems(List<PurchaseOrderItem> items, BigDecimal total) {
    }

    private BuiltItems buildItems(List<PurchaseOrderItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("订单明细不能为空");
        }
        Set<Long> seen = new HashSet<>();
        List<PurchaseOrderItem> toSave = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseOrderItemRequest it : items) {
            if (it.productId() == null) {
                throw BusinessException.badRequest("商品不能为空");
            }
            Product product = productRepository.findById(it.productId())
                    .orElseThrow(() -> BusinessException.badRequest("商品不存在: " + it.productId()));
            // R3 首营阻断：商品未过首营不可开单
            if (!"APPROVED".equals(product.getFirstAuditStatus())) {
                throw BusinessException.badRequest("商品未过首营审核，不可开采购单: " + product.getGenericName());
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
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setProductId(it.productId());
            item.setQuantity(qty);
            item.setUnitPrice(price);
            item.setAmount(amount);
            toSave.add(item);
            total = total.add(amount);
        }
        return new BuiltItems(toSave, total);
    }

    private PurchaseOrderItemVO toItemVO(PurchaseOrderItem item, Map<Long, String> productNames) {
        return new PurchaseOrderItemVO(item.getId(), item.getProductId(),
                productNames.get(item.getProductId()), item.getQuantity(),
                item.getUnitPrice(), item.getAmount());
    }

    private PurchaseOrderVO toVO(PurchaseOrder order, List<PurchaseOrderItemVO> items, String supplierName) {
        return new PurchaseOrderVO(order.getId(), order.getOrderNo(), order.getSupplierId(),
                supplierName, order.getOrderDate(), order.getStatus(), order.getTotalAmount(),
                order.getCreatedBy(), order.getCreatedAt(), items);
    }

    /** 供应商名称（单条）。 */
    private String supplierName(Long supplierId) {
        return supplierId == null ? null
                : supplierRepository.findById(supplierId).map(Supplier::getName).orElse(null);
    }

    /** 批量取供应商名称，避免列表 N+1（P1-30）。 */
    private Map<Long, String> supplierNames(List<Long> supplierIds) {
        Set<Long> ids = supplierIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return supplierRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName));
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
