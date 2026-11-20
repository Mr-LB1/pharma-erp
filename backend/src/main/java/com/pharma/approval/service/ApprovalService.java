package com.pharma.approval.service;

import com.pharma.approval.dto.ApprovalItemVO;
import com.pharma.approval.dto.ApprovalPendingVO;
import com.pharma.approval.dto.ApprovalRecordVO;
import com.pharma.approval.dto.ApprovalTarget;
import com.pharma.approval.entity.ApprovalRecord;
import com.pharma.approval.repository.ApprovalPendingQueryRepository;
import com.pharma.approval.repository.ApprovalRecordRepository;
import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Product;
import com.pharma.base.entity.Supplier;
import com.pharma.base.repository.CustomerRepository;
import com.pharma.base.repository.ProductRepository;
import com.pharma.base.repository.SupplierRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.DataScope;
import com.pharma.common.PageResult;
import com.pharma.inventory.entity.StockTransferItem;
import com.pharma.inventory.repository.StockTransferItemRepository;
import com.pharma.inventory.service.InventoryLedgerService;
import com.pharma.inventory.service.StockTransferService;
import com.pharma.purchase.entity.PurchaseOrder;
import com.pharma.purchase.entity.PurchaseOrderItem;
import com.pharma.purchase.entity.PurchaseReceiveItem;
import com.pharma.purchase.entity.PurchaseReturnItem;
import com.pharma.purchase.repository.PurchaseOrderItemRepository;
import com.pharma.purchase.repository.PurchaseOrderRepository;
import com.pharma.purchase.repository.PurchaseReceiveItemRepository;
import com.pharma.purchase.repository.PurchaseReturnItemRepository;
import com.pharma.purchase.service.PurchaseReceiveService;
import com.pharma.purchase.service.PurchaseReturnService;
import com.pharma.sales.entity.SalesDeliveryItem;
import com.pharma.sales.entity.SalesOrder;
import com.pharma.sales.entity.SalesOrderItem;
import com.pharma.sales.entity.SalesReturn;
import com.pharma.sales.entity.SalesReturnItem;
import com.pharma.sales.repository.SalesDeliveryItemRepository;
import com.pharma.sales.repository.SalesDeliveryRepository;
import com.pharma.sales.repository.SalesOrderItemRepository;
import com.pharma.sales.repository.SalesOrderRepository;
import com.pharma.sales.repository.SalesReturnItemRepository;
import com.pharma.sales.repository.SalesReturnRepository;
import com.pharma.sales.service.SalesDeliveryService;
import com.pharma.system.service.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审批流服务：采购/销售订单、销售退货、采购入库、销售出库、采购退货、仓库调拨的
 * SUBMITTED -> APPROVED / REJECTED，写 approval_record + 审计日志。
 * 事前控制：审批人不得为制单人（回避）；驳回必须留审批意见。
 * 批次十四：四类执行型单据（入库/出库/采购退货/调拨）审批通过后由各 Service 执行库存与财务动作。
 */
@Service
public class ApprovalService {

    public static final String BIZ_PURCHASE_ORDER = "PURCHASE_ORDER";
    public static final String BIZ_SALES_ORDER = "SALES_ORDER";
    public static final String BIZ_SALES_RETURN = "SALES_RETURN";
    public static final String BIZ_PURCHASE_RECEIVE = "PURCHASE_RECEIVE";
    public static final String BIZ_SALES_DELIVERY = "SALES_DELIVERY";
    public static final String BIZ_PURCHASE_RETURN = "PURCHASE_RETURN";
    public static final String BIZ_STOCK_TRANSFER = "STOCK_TRANSFER";

    private static final Set<String> SUPPORTED_BIZ_TYPES = Set.of(
            BIZ_PURCHASE_ORDER, BIZ_SALES_ORDER, BIZ_SALES_RETURN,
            BIZ_PURCHASE_RECEIVE, BIZ_SALES_DELIVERY, BIZ_PURCHASE_RETURN, BIZ_STOCK_TRANSFER);

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final SalesReturnRepository salesReturnRepository;
    private final SalesReturnItemRepository salesReturnItemRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final SupplierRepository supplierRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final AuditLogService auditLogService;
    private final InventoryLedgerService ledger;
    private final ApprovalPendingQueryRepository pendingQueryRepository;
    private final ApprovalLevelResolver approvalLevelResolver;
    private final PurchaseReceiveService purchaseReceiveService;
    private final SalesDeliveryService salesDeliveryService;
    private final PurchaseReturnService purchaseReturnService;
    private final StockTransferService stockTransferService;
    private final PurchaseReceiveItemRepository purchaseReceiveItemRepository;
    private final SalesDeliveryItemRepository salesDeliveryItemRepository;
    private final PurchaseReturnItemRepository purchaseReturnItemRepository;
    private final StockTransferItemRepository stockTransferItemRepository;
    private final SalesDeliveryRepository salesDeliveryRepository;

    public ApprovalService(PurchaseOrderRepository purchaseOrderRepository,
                           PurchaseOrderItemRepository purchaseOrderItemRepository,
                           SalesOrderRepository salesOrderRepository,
                           SalesOrderItemRepository salesOrderItemRepository,
                           SalesReturnRepository salesReturnRepository,
                           SalesReturnItemRepository salesReturnItemRepository,
                           ApprovalRecordRepository approvalRecordRepository,
                           SupplierRepository supplierRepository,
                           CustomerRepository customerRepository,
                           ProductRepository productRepository,
                           AuditLogService auditLogService,
                           InventoryLedgerService ledger,
                           ApprovalPendingQueryRepository pendingQueryRepository,
                           ApprovalLevelResolver approvalLevelResolver,
                           PurchaseReceiveService purchaseReceiveService,
                           SalesDeliveryService salesDeliveryService,
                           PurchaseReturnService purchaseReturnService,
                           StockTransferService stockTransferService,
                           PurchaseReceiveItemRepository purchaseReceiveItemRepository,
                           SalesDeliveryItemRepository salesDeliveryItemRepository,
                           PurchaseReturnItemRepository purchaseReturnItemRepository,
                           StockTransferItemRepository stockTransferItemRepository,
                           SalesDeliveryRepository salesDeliveryRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.salesReturnRepository = salesReturnRepository;
        this.salesReturnItemRepository = salesReturnItemRepository;
        this.approvalRecordRepository = approvalRecordRepository;
        this.supplierRepository = supplierRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.auditLogService = auditLogService;
        this.ledger = ledger;
        this.pendingQueryRepository = pendingQueryRepository;
        this.approvalLevelResolver = approvalLevelResolver;
        this.purchaseReceiveService = purchaseReceiveService;
        this.salesDeliveryService = salesDeliveryService;
        this.purchaseReturnService = purchaseReturnService;
        this.stockTransferService = stockTransferService;
        this.purchaseReceiveItemRepository = purchaseReceiveItemRepository;
        this.salesDeliveryItemRepository = salesDeliveryItemRepository;
        this.purchaseReturnItemRepository = purchaseReturnItemRepository;
        this.stockTransferItemRepository = stockTransferItemRepository;
        this.salesDeliveryRepository = salesDeliveryRepository;
    }

    @Transactional
    public ApprovalRecordVO approve(String bizType, Long bizId, String comment) {
        return action(bizType, bizId, "APPROVED", comment);
    }

    @Transactional
    public ApprovalRecordVO reject(String bizType, Long bizId, String comment) {
        return action(bizType, bizId, "REJECTED", comment);
    }

    private ApprovalRecordVO action(String bizType, Long bizId, String status, String comment) {
        if (!SUPPORTED_BIZ_TYPES.contains(bizType)) {
            throw BusinessException.badRequest("不支持的审批类型: " + bizType);
        }
        if ("REJECTED".equals(status) && (comment == null || comment.isBlank())) {
            throw BusinessException.badRequest("驳回必须填写审批意见");
        }
        String currentUser = ledger.currentUser();
        String orderNo;
        String createdBy;
        // P0-2：直接审批的订单/退货单先对单据头加行级悲观锁再复核状态，
        // 避免两人并发审批同一张单时都通过 SUBMITTED 守卫而重复推进/覆盖状态。
        // 执行型单据（入库/出库/采购退货/调拨）由各 Service 的 approveDocument 内部加锁。
        switch (bizType) {
            case BIZ_PURCHASE_ORDER -> {
                PurchaseOrder o = purchaseOrderRepository.findByIdForUpdate(bizId)
                        .orElseThrow(() -> BusinessException.notFound("采购订单不存在: " + bizId));
                if (!"SUBMITTED".equals(o.getStatus())) {
                    throw BusinessException.badRequest("仅已提交状态可审批，当前状态: " + o.getStatus());
                }
                checkNotSelfApproval(o.getCreatedBy(), currentUser);
                // P1-1 金额分级：按单据金额命中的层级校验审批角色
                approvalLevelResolver.requireApproverRole(BIZ_PURCHASE_ORDER, o.getTotalAmount());
                o.setStatus(status);
                purchaseOrderRepository.save(o);
                orderNo = o.getOrderNo();
                createdBy = o.getCreatedBy();
            }
            case BIZ_SALES_RETURN -> {
                SalesReturn r = salesReturnRepository.findByIdForUpdate(bizId)
                        .orElseThrow(() -> BusinessException.notFound("销售退货单不存在: " + bizId));
                if (!"SUBMITTED".equals(r.getStatus())) {
                    throw BusinessException.badRequest("仅已提交状态可审批，当前状态: " + r.getStatus());
                }
                checkNotSelfApproval(r.getCreatedBy(), currentUser);
                approvalLevelResolver.requireApproverRole(BIZ_SALES_RETURN, r.getTotalAmount());
                r.setStatus(status);
                salesReturnRepository.save(r);
                orderNo = r.getReturnNo();
                createdBy = r.getCreatedBy();
            }
            case BIZ_SALES_ORDER -> {
                SalesOrder o = salesOrderRepository.findByIdForUpdate(bizId)
                        .orElseThrow(() -> BusinessException.notFound("销售订单不存在: " + bizId));
                if (!"SUBMITTED".equals(o.getStatus())) {
                    throw BusinessException.badRequest("仅已提交状态可审批，当前状态: " + o.getStatus());
                }
                checkNotSelfApproval(o.getCreatedBy(), currentUser);
                approvalLevelResolver.requireApproverRole(BIZ_SALES_ORDER, o.getTotalAmount());
                o.setStatus(status);
                salesOrderRepository.save(o);
                orderNo = o.getOrderNo();
                createdBy = o.getCreatedBy();
            }
            default -> {
                // 执行型单据：入库 / 出库 / 采购退货 / 调拨 —— 审批通过仅推进状态并下发 WMS，
                // 库存与财务动作等 WMS 回传实量后由各 Service 的 wmsCallback 执行
                ApprovalTarget target = approvalTarget(bizType, bizId);
                if (!"SUBMITTED".equals(target.status())) {
                    throw BusinessException.badRequest("仅已提交状态可审批，当前状态: " + target.status());
                }
                checkNotSelfApproval(target.createdBy(), currentUser);
                approvalLevelResolver.requireApproverRole(bizType, target.amount());
                if ("APPROVED".equals(status)) {
                    approveDocument(bizType, bizId);
                } else {
                    rejectDocument(bizType, bizId);
                }
                orderNo = target.docNo();
                createdBy = target.createdBy();
            }
        }

        ApprovalRecord r = approvalRecordRepository
                .findFirstByBizTypeAndBizIdAndStatusOrderByIdDesc(bizType, bizId, "PENDING")
                .orElseGet(ApprovalRecord::new);
        r.setBizType(bizType);
        r.setBizId(bizId);
        r.setApprover(currentUser);
        r.setStatus(status);
        r.setComment(comment);
        r = approvalRecordRepository.save(r);

        auditLogService.log(currentUser, "ORDER_" + status, bizType, bizId,
                "审批 " + bizType + ":" + orderNo + "（制单人 " + createdBy + "） -> " + status,
                AuditLogService.json("orderNo", orderNo, "status", "SUBMITTED"),
                AuditLogService.json("orderNo", orderNo, "status", status, "comment", comment));
        return toVO(r);
    }

    private ApprovalTarget approvalTarget(String bizType, Long bizId) {
        return switch (bizType) {
            case BIZ_PURCHASE_RECEIVE -> purchaseReceiveService.approvalTarget(bizId);
            case BIZ_SALES_DELIVERY -> salesDeliveryService.approvalTarget(bizId);
            case BIZ_PURCHASE_RETURN -> purchaseReturnService.approvalTarget(bizId);
            case BIZ_STOCK_TRANSFER -> stockTransferService.approvalTarget(bizId);
            default -> throw BusinessException.badRequest("不支持的审批类型: " + bizType);
        };
    }

    /** 审批通过：APPROVED -> PUSHED（下发 WMS），仅状态推进，不动库存与财务。 */
    private void approveDocument(String bizType, Long bizId) {
        switch (bizType) {
            case BIZ_PURCHASE_RECEIVE -> purchaseReceiveService.approveDocument(bizId);
            case BIZ_SALES_DELIVERY -> salesDeliveryService.approveDocument(bizId);
            case BIZ_PURCHASE_RETURN -> purchaseReturnService.approveDocument(bizId);
            case BIZ_STOCK_TRANSFER -> stockTransferService.approveDocument(bizId);
            default -> throw BusinessException.badRequest("不支持的审批类型: " + bizType);
        }
    }

    private void rejectDocument(String bizType, Long bizId) {
        switch (bizType) {
            case BIZ_PURCHASE_RECEIVE -> purchaseReceiveService.reject(bizId);
            case BIZ_SALES_DELIVERY -> salesDeliveryService.reject(bizId);
            case BIZ_PURCHASE_RETURN -> purchaseReturnService.reject(bizId);
            case BIZ_STOCK_TRANSFER -> stockTransferService.reject(bizId);
            default -> throw BusinessException.badRequest("不支持的审批类型: " + bizType);
        }
    }

    /** 回避校验：审批人不得与制单人相同（P0-2）。 */
    private void checkNotSelfApproval(String createdBy, String currentUser) {
        if (createdBy != null && createdBy.equals(currentUser)) {
            throw BusinessException.forbidden("审批人不能与制单人相同，请由他人审批");
        }
    }

    /**
     * 待审批列表（#27 数据库分页）：count → 当前页单据头 → 按类型批量加载明细/商品/往来单位。
     * 排序由 SQL 完成（biz_id DESC, biz_type ASC），不再在 Java 侧全量排序；返回契约不变。
     *
     * <p>批次十（决策 5）：按审批人角色过滤——当前用户持有该单命中的 approver_role 才可见；
     * ADMIN 看全部；无认证上下文（内部调用/测试）不过滤。</p>
     * <p>批次十四：采购入库 / 销售出库 / 采购退货 / 仓库调拨 进入同一待审视图。</p>
     */
    public PageResult<ApprovalPendingVO> pending(int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);

        List<String> roleFilter = pendingRoleFilter();
        long total = pendingQueryRepository.count(roleFilter);
        if (total == 0) {
            return new PageResult<>(List.of(), 0, p, s);
        }
        List<ApprovalPendingQueryRepository.PendingRow> rows =
                pendingQueryRepository.page(roleFilter, (p - 1) * s, s);

        List<Long> purchaseIds = idsOf(rows, BIZ_PURCHASE_ORDER);
        List<Long> salesIds = idsOf(rows, BIZ_SALES_ORDER);
        List<Long> returnIds = idsOf(rows, BIZ_SALES_RETURN);
        List<Long> receiveIds = idsOf(rows, BIZ_PURCHASE_RECEIVE);
        List<Long> deliveryIds = idsOf(rows, BIZ_SALES_DELIVERY);
        List<Long> purchaseReturnIds = idsOf(rows, BIZ_PURCHASE_RETURN);
        List<Long> transferIds = idsOf(rows, BIZ_STOCK_TRANSFER);

        Map<Long, List<PurchaseOrderItem>> purchaseItems = purchaseIds.isEmpty() ? Map.of()
                : purchaseOrderItemRepository.findByOrderIdIn(purchaseIds).stream()
                .collect(Collectors.groupingBy(PurchaseOrderItem::getOrderId));
        Map<Long, List<SalesOrderItem>> salesItems = salesIds.isEmpty() ? Map.of()
                : salesOrderItemRepository.findByOrderIdIn(salesIds).stream()
                .collect(Collectors.groupingBy(SalesOrderItem::getOrderId));
        Map<Long, List<SalesReturnItem>> returnItems = returnIds.isEmpty() ? Map.of()
                : salesReturnItemRepository.findByReturnIdIn(returnIds).stream()
                .collect(Collectors.groupingBy(SalesReturnItem::getReturnId));
        Map<Long, List<PurchaseReceiveItem>> receiveItems = receiveIds.isEmpty() ? Map.of()
                : purchaseReceiveItemRepository.findByReceiveIdIn(receiveIds).stream()
                .collect(Collectors.groupingBy(PurchaseReceiveItem::getReceiveId));
        Map<Long, List<SalesDeliveryItem>> deliveryItems = deliveryIds.isEmpty() ? Map.of()
                : salesDeliveryItemRepository.findByDeliveryIdIn(deliveryIds).stream()
                .collect(Collectors.groupingBy(SalesDeliveryItem::getDeliveryId));
        Map<Long, List<PurchaseReturnItem>> purchaseReturnItems = purchaseReturnIds.isEmpty() ? Map.of()
                : purchaseReturnItemRepository.findByReturnIdIn(purchaseReturnIds).stream()
                .collect(Collectors.groupingBy(PurchaseReturnItem::getReturnId));
        Map<Long, List<StockTransferItem>> transferItems = transferIds.isEmpty() ? Map.of()
                : stockTransferItemRepository.findByTransferIdIn(transferIds).stream()
                .collect(Collectors.groupingBy(StockTransferItem::getTransferId));

        // 出库单待审明细只有品种/数量，单价取原销售订单单价
        Map<Long, Map<Long, BigDecimal>> priceByDelivery = new java.util.HashMap<>();
        if (!deliveryIds.isEmpty()) {
            Map<Long, Long> orderIdByDelivery = salesDeliveryRepository.findAllById(deliveryIds).stream()
                    .collect(Collectors.toMap(d -> d.getId(), d -> d.getOrderId()));
            Map<Long, Map<Long, BigDecimal>> priceByOrder = salesOrderItemRepository
                    .findByOrderIdIn(orderIdByDelivery.values().stream().distinct().toList()).stream()
                    .collect(Collectors.groupingBy(SalesOrderItem::getOrderId,
                            Collectors.toMap(SalesOrderItem::getProductId, SalesOrderItem::getUnitPrice,
                                    (a, b) -> a)));
            orderIdByDelivery.forEach((deliveryId, orderId) ->
                    priceByDelivery.put(deliveryId, priceByOrder.getOrDefault(orderId, Map.of())));
        }

        Set<Long> productIds = new HashSet<>();
        purchaseItems.values().forEach(list -> list.forEach(i -> productIds.add(i.getProductId())));
        salesItems.values().forEach(list -> list.forEach(i -> productIds.add(i.getProductId())));
        returnItems.values().forEach(list -> list.forEach(i -> productIds.add(i.getProductId())));
        receiveItems.values().forEach(list -> list.forEach(i -> productIds.add(i.getProductId())));
        deliveryItems.values().forEach(list -> list.forEach(i -> productIds.add(i.getProductId())));
        purchaseReturnItems.values().forEach(list -> list.forEach(i -> productIds.add(i.getProductId())));
        transferItems.values().forEach(list -> list.forEach(i -> productIds.add(i.getProductId())));
        Map<Long, String> productNames = productIds.isEmpty() ? Map.of()
                : productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, this::displayProductName));

        Set<Long> supplierIds = rows.stream().filter(r -> isSupplierParty(r.bizType()))
                .map(ApprovalPendingQueryRepository.PendingRow::partyId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> supplierNames = supplierIds.isEmpty() ? Map.of()
                : supplierRepository.findAllById(supplierIds).stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName));
        Set<Long> customerIds = rows.stream().filter(r -> isCustomerParty(r.bizType()))
                .map(ApprovalPendingQueryRepository.PendingRow::partyId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> customerNames = customerIds.isEmpty() ? Map.of()
                : customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getName));

        List<ApprovalPendingVO> records = new ArrayList<>(rows.size());
        for (ApprovalPendingQueryRepository.PendingRow row : rows) {
            String partyName = isSupplierParty(row.bizType())
                    ? supplierNames.get(row.partyId())
                    : customerNames.get(row.partyId());
            List<ApprovalItemVO> items = pendingItems(row, purchaseItems, salesItems, returnItems,
                    receiveItems, deliveryItems, purchaseReturnItems, transferItems, priceByDelivery, productNames);
            records.add(new ApprovalPendingVO(row.bizType(), row.bizId(), row.orderNo(), row.orderDate(),
                    row.totalAmount(), row.partyId(), partyName, row.createdBy(), items));
        }
        return new PageResult<>(records, total, p, s);
    }

    private List<ApprovalItemVO> pendingItems(ApprovalPendingQueryRepository.PendingRow row,
                                              Map<Long, List<PurchaseOrderItem>> purchaseItems,
                                              Map<Long, List<SalesOrderItem>> salesItems,
                                              Map<Long, List<SalesReturnItem>> returnItems,
                                              Map<Long, List<PurchaseReceiveItem>> receiveItems,
                                              Map<Long, List<SalesDeliveryItem>> deliveryItems,
                                              Map<Long, List<PurchaseReturnItem>> purchaseReturnItems,
                                              Map<Long, List<StockTransferItem>> transferItems,
                                              Map<Long, Map<Long, BigDecimal>> priceByDelivery,
                                              Map<Long, String> productNames) {
        return switch (row.bizType()) {
            case BIZ_PURCHASE_ORDER -> purchaseItems.getOrDefault(row.bizId(), List.of()).stream()
                    .map(i -> new ApprovalItemVO(i.getProductId(), productNames.get(i.getProductId()),
                            i.getQuantity(), i.getUnitPrice(), i.getAmount()))
                    .toList();
            case BIZ_SALES_ORDER -> salesItems.getOrDefault(row.bizId(), List.of()).stream()
                    .map(i -> new ApprovalItemVO(i.getProductId(), productNames.get(i.getProductId()),
                            i.getQuantity(), i.getUnitPrice(), i.getAmount()))
                    .toList();
            case BIZ_SALES_RETURN ->
                // 退货明细无 amount 列：按 quantity * unitPrice 计算，统一 2 位小数（与订单口径一致）
                    returnItems.getOrDefault(row.bizId(), List.of()).stream()
                            .map(i -> new ApprovalItemVO(i.getProductId(), productNames.get(i.getProductId()),
                                    i.getQuantity(), i.getUnitPrice(),
                                    i.getQuantity().multiply(i.getUnitPrice()).setScale(2, RoundingMode.HALF_UP)))
                            .toList();
            case BIZ_PURCHASE_RECEIVE -> receiveItems.getOrDefault(row.bizId(), List.of()).stream()
                    .map(i -> new ApprovalItemVO(i.getProductId(), productNames.get(i.getProductId()),
                            i.getQuantity(), i.getUnitCost(),
                            i.getQuantity().multiply(i.getUnitCost()).setScale(2, RoundingMode.HALF_UP)))
                    .toList();
            case BIZ_PURCHASE_RETURN -> purchaseReturnItems.getOrDefault(row.bizId(), List.of()).stream()
                    .map(i -> new ApprovalItemVO(i.getProductId(), productNames.get(i.getProductId()),
                            i.getQuantity(), i.getUnitCost(),
                            i.getQuantity().multiply(i.getUnitCost()).setScale(2, RoundingMode.HALF_UP)))
                    .toList();
            case BIZ_SALES_DELIVERY -> {
                Map<Long, BigDecimal> prices = priceByDelivery.getOrDefault(row.bizId(), Map.of());
                yield deliveryItems.getOrDefault(row.bizId(), List.of()).stream()
                        .map(i -> {
                            BigDecimal price = prices.getOrDefault(i.getProductId(), BigDecimal.ZERO);
                            return new ApprovalItemVO(i.getProductId(), productNames.get(i.getProductId()),
                                    i.getQuantity(), price,
                                    i.getQuantity().multiply(price).setScale(2, RoundingMode.HALF_UP));
                        })
                        .toList();
            }
            // 调拨无金额口径（内部移库），只展示品种与数量
            case BIZ_STOCK_TRANSFER -> transferItems.getOrDefault(row.bizId(), List.of()).stream()
                    .map(i -> new ApprovalItemVO(i.getProductId(), productNames.get(i.getProductId()),
                            i.getQuantity(), null, null))
                    .toList();
            default -> List.of();
        };
    }

    private static boolean isSupplierParty(String bizType) {
        return BIZ_PURCHASE_ORDER.equals(bizType) || BIZ_PURCHASE_RECEIVE.equals(bizType)
                || BIZ_PURCHASE_RETURN.equals(bizType);
    }

    private static boolean isCustomerParty(String bizType) {
        return BIZ_SALES_ORDER.equals(bizType) || BIZ_SALES_RETURN.equals(bizType)
                || BIZ_SALES_DELIVERY.equals(bizType);
    }

    /**
     * 待审角色过滤参数：null = 不过滤；空列表 = 当前用户无权查看任何待审单。
     * ADMIN 豁免；无认证上下文视为内部调用，保持全量。
     */
    private List<String> pendingRoleFilter() {
        if (!DataScope.hasCurrentUser() || DataScope.hasAuthority(DataScope.ROLE_ADMIN)) {
            return null;
        }
        Set<String> roles = DataScope.currentRoleCodes();
        return roles.isEmpty() ? List.of() : new ArrayList<>(roles);
    }

    private List<Long> idsOf(List<ApprovalPendingQueryRepository.PendingRow> rows, String bizType) {
        return rows.stream().filter(r -> bizType.equals(r.bizType()))
                .map(ApprovalPendingQueryRepository.PendingRow::bizId).toList();
    }

    private String displayProductName(Product p) {
        String generic = p.getGenericName() == null ? "" : p.getGenericName();
        String trade = p.getTradeName();
        if (trade == null || trade.isBlank() || trade.equals(generic)) {
            return generic;
        }
        return generic.isBlank() ? trade : generic + "（" + trade + "）";
    }

    private ApprovalRecordVO toVO(ApprovalRecord r) {
        return new ApprovalRecordVO(r.getId(), r.getBizType(), r.getBizId(), r.getNode(),
                r.getApprover(), r.getStatus(), r.getComment(), r.getCreatedAt());
    }
}
