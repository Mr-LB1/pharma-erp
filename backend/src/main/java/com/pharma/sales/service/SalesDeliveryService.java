package com.pharma.sales.service;

import com.pharma.approval.dto.ApprovalTarget;
import com.pharma.approval.entity.ApprovalRecord;
import com.pharma.approval.repository.ApprovalRecordRepository;
import com.pharma.approval.service.ApprovalLevelResolver;
import com.pharma.common.BusinessException;
import com.pharma.common.DataScope;
import com.pharma.common.DateValidator;
import com.pharma.common.PageResult;
import com.pharma.common.export.ExportPayload;
import com.pharma.common.export.ExportWriter;
import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Product;
import com.pharma.base.repository.CustomerRepository;
import com.pharma.base.repository.ProductRepository;
import com.pharma.finance.service.ArApService;
import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.repository.LotTraceRepository;
import com.pharma.inventory.repository.StockRepository;
import com.pharma.inventory.service.InventoryLedgerService;
import com.pharma.sales.dto.*;
import com.pharma.sales.entity.SalesDelivery;
import com.pharma.sales.entity.SalesDeliveryItem;
import com.pharma.sales.entity.SalesOrder;
import com.pharma.sales.entity.SalesOrderItem;
import com.pharma.sales.repository.SalesDeliveryItemRepository;
import com.pharma.sales.repository.SalesDeliveryRepository;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.pharma.inventory.service.InventoryLedgerService.OP_IN;
import static com.pharma.inventory.service.InventoryLedgerService.REF_SALES_DELIVERY;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_SALES_DELIVERY_ITEM;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_SALES_DELIVERY_VOID;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_STOCK;

/**
 * 销售出库服务：DRAFT（建单只记品种/数量）→ SUBMITTED（提审）→ APPROVED（审批）→ 执行出库 → COMPLETED。
 * 执行内容：FEFO 选批 + 效期阻断 + 库存不可为负 + 锁批出库 + 移动加权结转 + 生成应收，同一事务。
 * 审批通过前不动库存与应收（批次十四决策）；信用二次校验在建单与执行时各做一次。
 */
@Service
public class SalesDeliveryService {

    public static final String BIZ_TYPE = "SALES_DELIVERY";

    private final SalesDeliveryRepository deliveryRepository;
    private final SalesDeliveryItemRepository deliveryItemRepository;
    private final SalesOrderRepository orderRepository;
    private final SalesOrderItemRepository orderItemRepository;
    private final InventoryLedgerService ledger;
    private final LotTraceRepository lotTraceRepository;
    private final StockRepository stockRepository;
    private final AuditLogService auditLogService;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final ArApService arApService;
    private final DateValidator dateValidator;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final ApprovalLevelResolver approvalLevelResolver;

    public SalesDeliveryService(SalesDeliveryRepository deliveryRepository,
                                SalesDeliveryItemRepository deliveryItemRepository,
                                SalesOrderRepository orderRepository,
                                SalesOrderItemRepository orderItemRepository,
                                InventoryLedgerService ledger,
                                LotTraceRepository lotTraceRepository,
                                StockRepository stockRepository,
                                AuditLogService auditLogService,
                                CustomerRepository customerRepository,
                                ProductRepository productRepository,
                                ArApService arApService,
                                DateValidator dateValidator,
                                ApprovalRecordRepository approvalRecordRepository,
                                ApprovalLevelResolver approvalLevelResolver) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryItemRepository = deliveryItemRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.ledger = ledger;
        this.lotTraceRepository = lotTraceRepository;
        this.stockRepository = stockRepository;
        this.auditLogService = auditLogService;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.arApService = arApService;
        this.dateValidator = dateValidator;
        this.approvalRecordRepository = approvalRecordRepository;
        this.approvalLevelResolver = approvalLevelResolver;
    }

    /** 已校验的建单参数（create / update 共用）。 */
    private record Prepared(SalesOrder order, Long locationId, List<SalesDeliveryItemRequest> items,
                            BigDecimal receivable) {
    }

    /**
     * 建单：只写单据头 + 待出库品种/数量（批号留空占位），状态 DRAFT，不锁批、不扣库存、不生成应收。
     */
    @Transactional
    public SalesDeliveryVO create(SalesDeliveryCreateRequest req) {
        Prepared prepared = prepare(req);

        SalesDelivery delivery = new SalesDelivery();
        delivery.setDeliveryNo(ledger.genNo("DO"));
        delivery.setOrderId(prepared.order().getId());
        delivery.setDeliveryDate(req.deliveryDate() == null ? LocalDate.now() : req.deliveryDate());
        delivery.setStatus("DRAFT");
        delivery.setTotalAmount(prepared.receivable());
        delivery.setWarehouseId(prepared.locationId());
        delivery.setCreatedBy(ledger.currentUser());
        delivery = deliveryRepository.save(delivery);

        List<SalesDeliveryItem> saved = saveDraftItems(delivery.getId(), prepared.items());

        auditLogService.log(delivery.getCreatedBy(), "CREATE_DELIVERY", BIZ_TYPE, delivery.getId(),
                "创建销售出库单 " + delivery.getDeliveryNo() + "（待提交），金额 "
                        + delivery.getTotalAmount().toPlainString(),
                null, AuditLogService.json("deliveryNo", delivery.getDeliveryNo(), "status", "DRAFT",
                        "totalAmount", delivery.getTotalAmount(), "orderId", delivery.getOrderId()));

        Map<Long, String> names = productNames(saved.stream().map(SalesDeliveryItem::getProductId).toList());
        List<SalesDeliveryItemVO> vos = saved.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(delivery, vos, prepared.order().getOrderNo(), customerName(prepared.order().getCustomerId()));
    }

    /** 编辑：仅草稿或已驳回可改，整单重建待出库明细；已驳回改完仍为 REJECTED，需重新提交。 */
    @Transactional
    public SalesDeliveryVO update(Long id, SalesDeliveryCreateRequest req) {
        SalesDelivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售出库单不存在: " + id));
        if (!"DRAFT".equals(delivery.getStatus()) && !"REJECTED".equals(delivery.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回出库单可编辑，当前状态: " + delivery.getStatus());
        }
        Prepared prepared = prepare(req);
        delivery.setOrderId(prepared.order().getId());
        if (req.deliveryDate() != null) {
            delivery.setDeliveryDate(req.deliveryDate());
        }
        delivery.setTotalAmount(prepared.receivable());
        delivery.setWarehouseId(prepared.locationId());
        deliveryRepository.save(delivery);

        deliveryItemRepository.deleteAll(deliveryItemRepository.findByDeliveryId(id));
        List<SalesDeliveryItem> saved = saveDraftItems(id, prepared.items());

        Map<Long, String> names = productNames(saved.stream().map(SalesDeliveryItem::getProductId).toList());
        auditLogService.log(ledger.currentUser(), "UPDATE_DELIVERY", BIZ_TYPE, id,
                "编辑销售出库单 " + delivery.getDeliveryNo() + "，金额 " + delivery.getTotalAmount().toPlainString(),
                null, AuditLogService.json("deliveryNo", delivery.getDeliveryNo(), "status", delivery.getStatus(),
                        "totalAmount", delivery.getTotalAmount(), "itemCount", saved.size()));
        return toVO(delivery, saved.stream().map(i -> toItemVO(i, names)).toList(),
                prepared.order().getOrderNo(), customerName(prepared.order().getCustomerId()));
    }

    /** 提审：DRAFT / REJECTED -> SUBMITTED，写 PENDING approval_record（金额分级节点）。 */
    @Transactional
    public SalesDeliveryVO submit(Long id) {
        SalesDelivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售出库单不存在: " + id));
        if (!"DRAFT".equals(delivery.getStatus()) && !"REJECTED".equals(delivery.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回出库单可提交，当前状态: " + delivery.getStatus());
        }
        if (deliveryItemRepository.findByDeliveryId(id).isEmpty()) {
            throw BusinessException.badRequest("出库明细不能为空");
        }
        String before = delivery.getStatus();
        delivery.setStatus("SUBMITTED");
        deliveryRepository.save(delivery);

        ApprovalRecord record = new ApprovalRecord();
        record.setBizType(BIZ_TYPE);
        record.setBizId(delivery.getId());
        record.setNode(approvalLevelResolver.node(BIZ_TYPE, delivery.getTotalAmount()));
        record.setStatus("PENDING");
        approvalRecordRepository.save(record);

        auditLogService.log(ledger.currentUser(), "SUBMIT_DELIVERY", BIZ_TYPE, delivery.getId(),
                "提交销售出库单 " + delivery.getDeliveryNo() + "，金额 " + delivery.getTotalAmount().toPlainString(),
                AuditLogService.json("deliveryNo", delivery.getDeliveryNo(), "status", before),
                AuditLogService.json("deliveryNo", delivery.getDeliveryNo(), "status", "SUBMITTED",
                        "node", record.getNode()));
        return get(id);
    }

    /** 审批驳回：SUBMITTED -> REJECTED（可编辑后重新提交）。 */
    @Transactional
    public void reject(Long id) {
        SalesDelivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售出库单不存在: " + id));
        if (!"SUBMITTED".equals(delivery.getStatus())) {
            throw BusinessException.badRequest("仅已提交状态可驳回，当前状态: " + delivery.getStatus());
        }
        delivery.setStatus("REJECTED");
        deliveryRepository.save(delivery);
    }

    /** 审批上下文（状态守卫 / 回避校验 / 金额分级）。 */
    public ApprovalTarget approvalTarget(Long id) {
        SalesDelivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售出库单不存在: " + id));
        return new ApprovalTarget(delivery.getDeliveryNo(), delivery.getStatus(), delivery.getCreatedBy(),
                delivery.getTotalAmount());
    }

    /**
     * 审批通过：SUBMITTED -> APPROVED -> PUSHED（下发 WMS），仅状态推进，不锁批、不扣库存、不生成应收。
     */
    @Transactional
    public void approveDocument(Long id) {
        SalesDelivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售出库单不存在: " + id));
        if (!"SUBMITTED".equals(delivery.getStatus())) {
            throw BusinessException.badRequest("仅已提交状态可审批，当前状态: " + delivery.getStatus());
        }
        delivery.setStatus("APPROVED");
        deliveryRepository.save(delivery);
        delivery.setStatus("PUSHED");
        delivery.setWmsPushedAt(java.time.LocalDateTime.now());
        deliveryRepository.save(delivery);

        auditLogService.log(ledger.currentUser(), "DELIVERY_PUSHED", BIZ_TYPE, delivery.getId(),
                "销售出库单 " + delivery.getDeliveryNo() + " 审批通过并下发 WMS，金额 "
                        + delivery.getTotalAmount().toPlainString(),
                AuditLogService.json("deliveryNo", delivery.getDeliveryNo(), "status", "SUBMITTED"),
                AuditLogService.json("deliveryNo", delivery.getDeliveryNo(), "status", "PUSHED",
                        "totalAmount", delivery.getTotalAmount()));
    }

    /**
     * WMS 回传：仅 PUSHED 可回传；按实发量 FEFO 出库并置 COMPLETED；重复回传 400。
     */
    @Transactional
    public SalesDeliveryVO wmsCallback(Long id, com.pharma.common.WmsCallbackRequest req) {
        SalesDelivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售出库单不存在: " + id));
        if (!"PUSHED".equals(delivery.getStatus())) {
            throw BusinessException.badRequest("仅已下发 WMS 的单据可回传实量，当前状态: " + delivery.getStatus());
        }
        Map<Long, BigDecimal> actual = resolveActualQty(deliveryItemRepository.findByDeliveryId(id), req);
        delivery.setWmsNo(req == null ? null : req.wmsNo());
        delivery.setWmsCallbackAt(req == null || req.callbackTime() == null
                ? java.time.LocalDateTime.now() : req.callbackTime());
        deliveryRepository.save(delivery);

        executeActual(id, actual);
        return get(id);
    }

    /**
     * 按 WMS 实发量执行出库：草稿占位行按实量 FEFO 重写为真实批次行。
     * 行级差异：每个品种首个批次行记录 qty_diff = 实发合计 - 单据量，其余批次行 qty_diff=0；
     * actual_qty 为该批次实际出库量（Σ actual_qty = 实发合计）。
     */
    @Transactional
    public SalesDeliveryVO executeActual(Long id, Map<Long, BigDecimal> actualQtyByItemId) {
        SalesDelivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售出库单不存在: " + id));
        if (!"PUSHED".equals(delivery.getStatus())) {
            throw BusinessException.badRequest("仅已下发 WMS 的单据可执行出库，当前状态: " + delivery.getStatus());
        }
        SalesOrder order = orderRepository.findById(delivery.getOrderId())
                .orElseThrow(() -> BusinessException.notFound("销售订单不存在: " + delivery.getOrderId()));
        Long locationId = delivery.getWarehouseId();
        if (locationId == null) {
            throw BusinessException.badRequest("出库仓库缺失，无法执行出库: " + delivery.getDeliveryNo());
        }
        List<SalesDeliveryItem> drafts = deliveryItemRepository.findByDeliveryId(id);
        if (drafts.isEmpty()) {
            throw BusinessException.badRequest("出库明细不能为空");
        }

        // 单据量 / 实发量按品种汇总（建单时已保证同品种不重复）
        Map<Long, BigDecimal> requestedByProduct = new java.util.LinkedHashMap<>();
        Map<Long, BigDecimal> actualByProduct = new java.util.LinkedHashMap<>();
        for (SalesDeliveryItem d : drafts) {
            BigDecimal actual = actualQtyByItemId == null || actualQtyByItemId.get(d.getId()) == null
                    ? d.getQuantity() : actualQtyByItemId.get(d.getId());
            requestedByProduct.merge(d.getProductId(), d.getQuantity(), BigDecimal::add);
            actualByProduct.merge(d.getProductId(), actual, BigDecimal::add);
        }

        Map<Long, BigDecimal> priceByProduct = orderItemRepository.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(SalesOrderItem::getProductId, SalesOrderItem::getUnitPrice,
                        (a, b) -> a));
        BigDecimal receivable = actualByProduct.entrySet().stream()
                .map(e -> e.getValue().multiply(priceByProduct.getOrDefault(e.getKey(), BigDecimal.ZERO)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        checkCreditLimit(order.getCustomerId(), receivable);

        // 草稿行是「品种 + 数量」占位，执行时按 FEFO 实际批次重建
        deliveryItemRepository.deleteAll(drafts);

        String opUser = ledger.currentUser();
        Map<Long, String> productNames = productNames(new java.util.ArrayList<>(actualByProduct.keySet()));
        List<SalesDeliveryItemVO> vos = new ArrayList<>();

        for (Map.Entry<Long, BigDecimal> entry : actualByProduct.entrySet()) {
            Long productId = entry.getKey();
            BigDecimal actual = entry.getValue();
            if (actual.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            List<InventoryLedgerService.BatchAllocation> allocations =
                    ledger.allocateOutbound(productId, actual, locationId, opUser,
                            REF_SALES_DELIVERY, delivery.getId());
            boolean first = true;
            for (InventoryLedgerService.BatchAllocation alloc : allocations) {
                SalesDeliveryItem di = new SalesDeliveryItem();
                di.setDeliveryId(delivery.getId());
                di.setProductId(productId);
                di.setBatchNo(alloc.batchNo());
                di.setQuantity(alloc.quantity());
                di.setUnitCost(alloc.unitCost().setScale(2, RoundingMode.HALF_UP));
                di.setActualQty(alloc.quantity());
                // 行级差异只记在该品种首个批次行，避免重复累计
                di.setQtyDiff(first
                        ? actual.subtract(requestedByProduct.getOrDefault(productId, BigDecimal.ZERO))
                        : BigDecimal.ZERO);
                first = false;
                di = deliveryItemRepository.save(di);

                ledger.writeLotTrace(productId, alloc.batchNo(), TRACE_STOCK, alloc.stockId(),
                        TRACE_SALES_DELIVERY_ITEM, di.getId(), alloc.quantity(), opUser);

                vos.add(new SalesDeliveryItemVO(di.getId(), di.getProductId(),
                        productNames.get(di.getProductId()), di.getBatchNo(),
                        di.getQuantity(), visibleCost(di.getUnitCost()),
                        di.getActualQty(), di.getQtyDiff()));
            }
        }

        BigDecimal beforeTotal = delivery.getTotalAmount();
        delivery.setTotalAmount(receivable.setScale(2, RoundingMode.HALF_UP));
        // 先落 COMPLETED 再重算订单状态：重算只统计已执行（COMPLETED/ACTIVE）的出库单
        delivery.setStatus("COMPLETED");
        deliveryRepository.save(delivery);

        advanceOrderStatus(order);
        arApService.generateAr(order.getCustomerId(), REF_SALES_DELIVERY, delivery.getId(),
                delivery.getTotalAmount());

        auditLogService.log(opUser, "DELIVERY_COMPLETED", BIZ_TYPE, delivery.getId(),
                "销售出库单 " + delivery.getDeliveryNo() + " WMS 回传完成，实发金额 "
                        + delivery.getTotalAmount().toPlainString() + "（单据预估 " + beforeTotal.toPlainString() + "）",
                AuditLogService.json("deliveryNo", delivery.getDeliveryNo(), "status", "PUSHED",
                        "totalAmount", beforeTotal),
                AuditLogService.json("deliveryNo", delivery.getDeliveryNo(), "status", "COMPLETED",
                        "totalAmount", delivery.getTotalAmount(), "wmsNo", delivery.getWmsNo(),
                        "itemCount", vos.size()));

        return toVO(delivery, vos, order.getOrderNo(), customerName(order.getCustomerId()));
    }

    /** 回传实量解析：未提供明细则全部按单据量；提供则校验归属与非负。 */
    private Map<Long, BigDecimal> resolveActualQty(List<SalesDeliveryItem> items,
                                                   com.pharma.common.WmsCallbackRequest req) {
        Map<Long, BigDecimal> actual = new HashMap<>();
        if (req == null || req.items() == null || req.items().isEmpty()) {
            for (SalesDeliveryItem it : items) {
                actual.put(it.getId(), it.getQuantity());
            }
            return actual;
        }
        Map<Long, SalesDeliveryItem> byId = new HashMap<>();
        for (SalesDeliveryItem it : items) {
            byId.put(it.getId(), it);
        }
        for (com.pharma.common.WmsCallbackRequest.Item it : req.items()) {
            if (it == null || it.itemId() == null) {
                throw BusinessException.badRequest("回传明细 itemId 不能为空");
            }
            if (!byId.containsKey(it.itemId())) {
                throw BusinessException.badRequest("回传明细不属于该出库单: itemId=" + it.itemId());
            }
            BigDecimal qty = it.actualQty() == null ? BigDecimal.ZERO : it.actualQty();
            if (qty.compareTo(BigDecimal.ZERO) < 0) {
                throw BusinessException.badRequest("回传实际数量不能为负: itemId=" + it.itemId());
            }
            actual.put(it.itemId(), qty);
        }
        for (SalesDeliveryItem it : items) {
            actual.putIfAbsent(it.getId(), it.getQuantity());
        }
        return actual;
    }

    /**
     * 信用二次校验（P2-8，方案 A）：未结应收 + 本次出库金额 > creditLimit → 400。
     * 只拦额度不拦单量（与「超发允许、库存非负」并行）；creditLimit 为空视为不限。
     */
    private void checkCreditLimit(Long customerId, BigDecimal receivable) {
        Customer customer = customerRepository.findById(customerId).orElse(null);
        if (customer == null || customer.getCreditLimit() == null) {
            return;
        }
        BigDecimal outstanding = arApService.outstanding(customerId);
        BigDecimal exposure = outstanding.add(receivable);
        if (exposure.compareTo(customer.getCreditLimit()) > 0) {
            throw BusinessException.badRequest("超出信用额度（出库二次校验）：未结应收 "
                    + outstanding.toPlainString() + " + 本次出库 " + receivable.toPlainString()
                    + " > 信用额度 " + customer.getCreditLimit().toPlainString()
                    + "（客户 " + customer.getName() + "）");
        }
    }

    public PageResult<SalesDeliveryVO> list(String keyword, int page, int size) {
        return list(keyword, null, page, size);
    }

    /** 列表（批次十四：支持按状态过滤）。 */
    public PageResult<SalesDeliveryVO> list(String keyword, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<SalesDelivery> result = deliveryRepository.search(
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                status == null || status.isBlank() ? null : status.trim(), pageable);

        List<Long> ids = result.getContent().stream().map(SalesDelivery::getId).toList();
        Map<Long, List<SalesDeliveryItem>> itemMap = deliveryItemRepository.findByDeliveryIdIn(ids).stream()
                .collect(Collectors.groupingBy(SalesDeliveryItem::getDeliveryId));

        Map<Long, SalesOrder> orders = orderRepository.findAllById(result.getContent().stream()
                        .map(SalesDelivery::getOrderId).toList()).stream()
                .collect(Collectors.toMap(SalesOrder::getId, o -> o));
        Map<Long, String> customerNames = customerNames(orders.values().stream()
                .map(SalesOrder::getCustomerId).toList());
        Map<Long, String> productNames = productNames(itemMap.values().stream().flatMap(List::stream)
                .map(SalesDeliveryItem::getProductId).toList());

        List<SalesDeliveryVO> records = result.getContent().stream()
                .map(d -> {
                    SalesOrder order = orders.get(d.getOrderId());
                    return toVO(d, itemMap.getOrDefault(d.getId(), List.of()).stream()
                                    .map(i -> toItemVO(i, productNames)).toList(),
                            order == null ? null : order.getOrderNo(),
                            order == null ? null : customerNames.get(order.getCustomerId()));
                })
                .toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    /** 出库单详情：含明细、关联订单号与客户名；不存在返回 404（BusinessException.notFound）。 */
    public SalesDeliveryVO get(Long id) {
        SalesDelivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售出库单不存在: " + id));
        SalesOrder order = orderRepository.findById(delivery.getOrderId()).orElse(null);
        List<SalesDeliveryItem> items = deliveryItemRepository.findByDeliveryId(id);
        Map<Long, String> names = productNames(items.stream().map(SalesDeliveryItem::getProductId).toList());
        return toVO(delivery, items.stream().map(i -> toItemVO(i, names)).toList(),
                order == null ? null : order.getOrderNo(),
                order == null ? null : customerName(order.getCustomerId()));
    }

    /**
     * 导出用全量查询（上限 {@link ExportWriter#MAX_ROWS}）：复用列表的查询条件，不分页。
     * 金额按原订单单价 × 出库数量计算（销售金额），非库存成本。
     */
    public ExportPayload<SalesDeliveryExportRow> exportRows(String keyword) {
        return exportRows(keyword, null);
    }

    public ExportPayload<SalesDeliveryExportRow> exportRows(String keyword, String status) {
        Pageable pageable = PageRequest.of(0, ExportWriter.MAX_ROWS, Sort.by(Sort.Direction.DESC, "id"));
        Page<SalesDelivery> result = deliveryRepository.search(
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                status == null || status.isBlank() ? null : status.trim(), pageable);
        List<SalesDelivery> content = result.getContent();
        List<Long> ids = content.stream().map(SalesDelivery::getId).toList();
        Map<Long, List<SalesDeliveryItem>> itemMap = deliveryItemRepository.findByDeliveryIdIn(ids).stream()
                .collect(Collectors.groupingBy(SalesDeliveryItem::getDeliveryId));
        Map<Long, SalesOrder> orders = orderRepository.findAllById(content.stream()
                        .map(SalesDelivery::getOrderId).toList()).stream()
                .collect(Collectors.toMap(SalesOrder::getId, o -> o));
        Map<Long, String> customerNames = customerNames(orders.values().stream()
                .map(SalesOrder::getCustomerId).toList());
        Map<Long, Map<Long, BigDecimal>> priceByOrder = orderItemRepository
                .findByOrderIdIn(orders.keySet()).stream()
                .collect(Collectors.groupingBy(SalesOrderItem::getOrderId,
                        Collectors.toMap(SalesOrderItem::getProductId, SalesOrderItem::getUnitPrice, (a, b) -> a)));
        List<SalesDeliveryExportRow> rows = content.stream()
                .map(d -> {
                    SalesOrder order = orders.get(d.getOrderId());
                    Map<Long, BigDecimal> prices = priceByOrder.getOrDefault(d.getOrderId(), Map.of());
                    return new SalesDeliveryExportRow(d.getDeliveryNo(), billDate(d.getDeliveryDate()),
                            order == null ? null : customerNames.get(order.getCustomerId()),
                            deliveryAmount(itemMap.getOrDefault(d.getId(), List.of()), prices),
                            d.getStatus(), d.getCreatedBy());
                })
                .toList();
        return new ExportPayload<>(rows, result.getTotalElements(), ExportWriter.MAX_ROWS);
    }

    /** 出库金额：Σ（明细数量 × 原订单单价）；订单单价缺失按 0 计。 */
    private static BigDecimal deliveryAmount(List<SalesDeliveryItem> items, Map<Long, BigDecimal> prices) {
        return items.stream()
                .map(i -> i.getQuantity().multiply(prices.getOrDefault(i.getProductId(), BigDecimal.ZERO)))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String billDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    /**
     * R8 作废红冲：COMPLETED（含历史 ACTIVE）→ 反向库存流水 + 反向成本台账 + lot_trace 作废记录 + 应收联动作废；
     * DRAFT / REJECTED → 仅置 VOID（未执行出库，无库存/财务影响）；SUBMITTED → 拒绝，需先驳回或完成审批。
     * reason 写入审计日志留痕。
     */
    @Transactional
    public SalesDeliveryVO voidDelivery(Long id, String reason) {
        SalesDelivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售出库单不存在: " + id));
        SalesOrder order = orderRepository.findById(delivery.getOrderId()).orElse(null);
        // IDOR 防护：出库单制单人或关联销售单制单人（特权身份豁免）
        DataScope.requireAnyOwnership(delivery.getCreatedBy(), order == null ? null : order.getCreatedBy());
        if ("VOID".equals(delivery.getStatus())) {
            throw BusinessException.badRequest("出库单已作废: " + delivery.getDeliveryNo());
        }
        if ("SUBMITTED".equals(delivery.getStatus())) {
            throw BusinessException.badRequest("待审批出库单不可直接作废，请先驳回或完成审批: "
                    + delivery.getDeliveryNo());
        }
        if ("PUSHED".equals(delivery.getStatus())) {
            throw BusinessException.badRequest("已下发 WMS 的出库单不可直接作废，请等待回传完成后再处理: "
                    + delivery.getDeliveryNo());
        }

        String before = delivery.getStatus();
        String opUser = ledger.currentUser();
        List<SalesDeliveryItem> items = deliveryItemRepository.findByDeliveryId(id);
        boolean executed = "COMPLETED".equals(before) || "ACTIVE".equals(before);
        // 先置 VOID 再重算订单状态：重算只统计已执行的出库单，避免把本单仍计入
        delivery.setStatus("VOID");
        deliveryRepository.save(delivery);
        if (executed) {
            for (SalesDeliveryItem di : items) {
                Long stockId = lotTraceRepository
                        .findFirstByProductIdAndBatchNoAndTargetTypeAndTargetIdAndSourceType(
                                di.getProductId(), di.getBatchNo(), TRACE_SALES_DELIVERY_ITEM, di.getId(), TRACE_STOCK)
                        .map(t -> t.getSourceId())
                        .orElseThrow(() -> BusinessException.badRequest(
                                "未找到出库追溯，无法作废: item=" + di.getId()));
                Stock stock = stockRepository.findById(stockId)
                        .orElseThrow(() -> BusinessException.badRequest("库存批次不存在: " + stockId));

                BigDecimal qty = di.getQuantity();
                BigDecimal beforeQty = stock.getQuantity();
                BigDecimal after = beforeQty.add(qty);
                stock.setQuantity(after);
                stockRepository.save(stock);

                ledger.writeStockTransaction(di.getProductId(), di.getBatchNo(), OP_IN, REF_SALES_DELIVERY,
                        delivery.getId(), stock.getLocationId(), qty, beforeQty, after, opUser);

                ledger.applyCostIn(di.getProductId(), di.getBatchNo(), qty, di.getUnitCost(),
                        ledger.currentBalance(di.getProductId()));

                ledger.writeLotTrace(di.getProductId(), di.getBatchNo(), TRACE_STOCK, stockId,
                        TRACE_SALES_DELIVERY_VOID, delivery.getId(), qty, opUser);
            }

            // 财务联动：应收单据联动作废
            arApService.voidByBiz(REF_SALES_DELIVERY, delivery.getId());

            // P1-19：作废后按有效出库单重算订单状态（可能由 PARTIAL_DELIVERED 回退到 APPROVED）
            if (order != null) {
                advanceOrderStatus(order);
            }
        }

        auditLogService.log(opUser, "VOID_DELIVERY", REF_SALES_DELIVERY, delivery.getId(),
                "作废销售出库单 " + delivery.getDeliveryNo() + (reason == null || reason.isBlank()
                        ? "" : "，原因：" + reason.trim()),
                AuditLogService.json("deliveryNo", delivery.getDeliveryNo(), "status", before,
                        "itemCount", items.size(), "totalQuantity", totalQuantity(items)),
                AuditLogService.json("deliveryNo", delivery.getDeliveryNo(), "status", "VOID",
                        "itemCount", items.size(), "totalQuantity", totalQuantity(items), "reason", reason));

        Map<Long, String> names = productNames(items.stream()
                .map(SalesDeliveryItem::getProductId).toList());
        List<SalesDeliveryItemVO> vos = items.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(delivery, vos,
                order == null ? null : order.getOrderNo(),
                order == null ? null : customerName(order.getCustomerId()));
    }

    /** 建单/编辑共用校验：订单状态、业务日期、明细品种与信用额度二次校验。 */
    private Prepared prepare(SalesDeliveryCreateRequest req) {
        if (req.warehouseId() == null) {
            throw BusinessException.badRequest("出库仓库不能为空");
        }
        dateValidator.validateBizDate(req.deliveryDate(), "出库日期");
        Long locationId = ledger.requireLocationId(req.warehouseId());
        if (req.orderId() == null) {
            throw BusinessException.badRequest("销售订单不能为空");
        }
        SalesOrder order = orderRepository.findById(req.orderId())
                .orElseThrow(() -> BusinessException.notFound("销售订单不存在: " + req.orderId()));
        String orderStatus = order.getStatus();
        if ("REJECTED".equals(orderStatus)) {
            throw BusinessException.badRequest("订单已驳回，不可出库");
        }
        if ("SUBMITTED".equals(orderStatus)) {
            throw BusinessException.badRequest("订单待审批（SUBMITTED），审核通过后方可出库");
        }
        if (!"APPROVED".equals(orderStatus) && !"PARTIAL_DELIVERED".equals(orderStatus)) {
            throw BusinessException.badRequest(
                    "订单状态 " + orderStatus + " 不可出库，需为 APPROVED / PARTIAL_DELIVERED");
        }

        List<SalesDeliveryItemRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("出库明细不能为空");
        }

        Map<Long, BigDecimal> orderQty = orderItemRepository.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(SalesOrderItem::getProductId, SalesOrderItem::getQuantity,
                        BigDecimal::add));
        Set<Long> seen = new HashSet<>();
        for (SalesDeliveryItemRequest it : items) {
            if (it.productId() == null) {
                throw BusinessException.badRequest("商品不能为空");
            }
            if (!orderQty.containsKey(it.productId())) {
                throw BusinessException.badRequest("商品不在销售订单中: " + it.productId());
            }
            if (!seen.add(it.productId())) {
                throw BusinessException.badRequest("明细商品重复: " + it.productId());
            }
            if (it.quantity() == null || it.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("出库数量必须大于0");
            }
        }

        // 信用二次校验（P2-8）：订单通过后可能改价/分次出库，出库前按「未结应收 + 本次出库金额」再拦一次
        Map<Long, BigDecimal> priceByProduct = orderItemRepository.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(SalesOrderItem::getProductId, SalesOrderItem::getUnitPrice,
                        (a, b) -> a));
        BigDecimal receivable = items.stream()
                .map(it -> it.quantity().multiply(priceByProduct.getOrDefault(it.productId(), BigDecimal.ZERO)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        checkCreditLimit(order.getCustomerId(), receivable);

        return new Prepared(order, locationId, items, receivable.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * 草稿明细占位：批号与成本在审批执行（FEFO）时才确定，此处先记品种 + 数量。
     * batch_no 非空约束用占位符 "-" 满足；执行时整批删除重建。
     */
    private List<SalesDeliveryItem> saveDraftItems(Long deliveryId, List<SalesDeliveryItemRequest> items) {
        List<SalesDeliveryItem> saved = new ArrayList<>();
        for (SalesDeliveryItemRequest it : items) {
            SalesDeliveryItem di = new SalesDeliveryItem();
            di.setDeliveryId(deliveryId);
            di.setProductId(it.productId());
            di.setBatchNo("-");
            di.setQuantity(it.quantity());
            di.setUnitCost(BigDecimal.ZERO);
            saved.add(deliveryItemRepository.save(di));
        }
        return saved;
    }

    /**
     * 按有效出库单（已执行 COMPLETED / 历史 ACTIVE，排除已作废与未审批）重算订单状态：
     * 无有效出库 -> APPROVED，发满 -> COMPLETED，否则 PARTIAL_DELIVERED。
     */
    private void advanceOrderStatus(SalesOrder order) {
        Map<Long, BigDecimal> orderedQty = orderItemRepository.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(SalesOrderItem::getProductId, SalesOrderItem::getQuantity,
                        BigDecimal::add));

        List<Long> deliveryIds = deliveryRepository.findByOrderId(order.getId()).stream()
                .filter(d -> "COMPLETED".equals(d.getStatus()) || "ACTIVE".equals(d.getStatus()))
                .map(SalesDelivery::getId).toList();
        Map<Long, BigDecimal> deliveredQty = new HashMap<>();
        for (SalesDeliveryItem di : deliveryItemRepository.findByDeliveryIdIn(deliveryIds)) {
            deliveredQty.merge(di.getProductId(), di.getQuantity(), BigDecimal::add);
        }

        BigDecimal totalDelivered = deliveredQty.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDelivered.compareTo(BigDecimal.ZERO) <= 0) {
            order.setStatus("APPROVED");
        } else {
            boolean completed = orderedQty.entrySet().stream().allMatch(e ->
                    deliveredQty.getOrDefault(e.getKey(), BigDecimal.ZERO).compareTo(e.getValue()) >= 0);
            order.setStatus(completed ? "COMPLETED" : "PARTIAL_DELIVERED");
        }
        orderRepository.save(order);
    }

    private SalesDeliveryItemVO toItemVO(SalesDeliveryItem item, Map<Long, String> productNames) {
        return new SalesDeliveryItemVO(item.getId(), item.getProductId(),
                productNames.get(item.getProductId()), item.getBatchNo(),
                item.getQuantity(), visibleCost(item.getUnitCost()),
                item.getActualQty(), item.getQtyDiff());
    }

    /** 成本脱敏（P1-23）：无 inventory:cost:view 权限返回 null。 */
    private BigDecimal visibleCost(BigDecimal unitCost) {
        return DataScope.canViewCost() ? unitCost : null;
    }

    private SalesDeliveryVO toVO(SalesDelivery delivery, List<SalesDeliveryItemVO> items,
                                 String orderNo, String customerName) {
        return new SalesDeliveryVO(delivery.getId(), delivery.getDeliveryNo(), delivery.getOrderId(),
                orderNo, delivery.getDeliveryDate(), delivery.getStatus(), customerName, delivery.getCreatedBy(),
                delivery.getCreatedAt(), delivery.getWmsNo(), delivery.getWmsPushedAt(),
                delivery.getWmsCallbackAt(), items);
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

    /** 审计快照用：明细数量合计。 */
    private static BigDecimal totalQuantity(List<SalesDeliveryItem> items) {
        return items.stream().map(SalesDeliveryItem::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
