package com.pharma.purchase.service;

import com.pharma.approval.dto.ApprovalTarget;
import com.pharma.approval.entity.ApprovalRecord;
import com.pharma.approval.repository.ApprovalRecordRepository;
import com.pharma.approval.service.ApprovalLevelResolver;
import com.pharma.base.entity.Product;
import com.pharma.base.entity.Supplier;
import com.pharma.base.entity.Warehouse;
import com.pharma.base.repository.ProductRepository;
import com.pharma.base.repository.SupplierRepository;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.DataScope;
import com.pharma.common.DateValidator;
import com.pharma.common.PageResult;
import com.pharma.common.export.ExportPayload;
import com.pharma.common.export.ExportWriter;
import com.pharma.finance.service.ArApService;
import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.repository.LotTraceRepository;
import com.pharma.inventory.repository.StockRepository;
import com.pharma.inventory.service.InventoryLedgerService;
import com.pharma.purchase.dto.*;
import com.pharma.purchase.entity.PurchaseOrder;
import com.pharma.purchase.entity.PurchaseOrderItem;
import com.pharma.purchase.entity.PurchaseReceive;
import com.pharma.purchase.entity.PurchaseReceiveItem;
import com.pharma.purchase.repository.PurchaseOrderItemRepository;
import com.pharma.purchase.repository.PurchaseOrderRepository;
import com.pharma.purchase.repository.PurchaseReceiveItemRepository;
import com.pharma.purchase.repository.PurchaseReceiveRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.pharma.inventory.service.InventoryLedgerService.*;

/**
 * 采购入库服务：DRAFT（建单只记明细）→ SUBMITTED（提审）→ APPROVED（审批）→ 执行入库 → COMPLETED。
 * 执行内容：建批次库存 + 移动加权 + 写三张流水 + 推进订单状态 + 生成应付，同一事务。
 * 审批通过前不动库存与应付（批次十四决策）。
 */
@Service
public class PurchaseReceiveService {

    public static final String BIZ_TYPE = "PURCHASE_RECEIVE";

    /**
     * 温控兼容矩阵：ROOM_TEMP 只能入常温仓；COOL 可入阴凉/冷藏；COLD 只能入冷藏仓。
     * 反向（如冷藏药品入常温仓）一律拒绝。
     */
    private static final Map<String, Set<String>> ALLOWED_WAREHOUSE_TEMP = Map.of(
            "ROOM_TEMP", Set.of("ROOM_TEMP"),
            "COOL", Set.of("COOL", "COLD"),
            "COLD", Set.of("COLD"));

    private final PurchaseReceiveRepository receiveRepository;
    private final PurchaseReceiveItemRepository receiveItemRepository;
    private final PurchaseOrderRepository orderRepository;
    private final PurchaseOrderItemRepository orderItemRepository;
    private final InventoryLedgerService ledger;
    private final LotTraceRepository lotTraceRepository;
    private final StockRepository stockRepository;
    private final AuditLogService auditLogService;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final SupplierRepository supplierRepository;
    private final ArApService arApService;
    private final DateValidator dateValidator;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final ApprovalLevelResolver approvalLevelResolver;

    public PurchaseReceiveService(PurchaseReceiveRepository receiveRepository,
                                  PurchaseReceiveItemRepository receiveItemRepository,
                                  PurchaseOrderRepository orderRepository,
                                  PurchaseOrderItemRepository orderItemRepository,
                                  InventoryLedgerService ledger,
                                  LotTraceRepository lotTraceRepository,
                                  StockRepository stockRepository,
                                  AuditLogService auditLogService,
                                  ProductRepository productRepository,
                                  WarehouseRepository warehouseRepository,
                                  SupplierRepository supplierRepository,
                                  ArApService arApService,
                                  DateValidator dateValidator,
                                  ApprovalRecordRepository approvalRecordRepository,
                                  ApprovalLevelResolver approvalLevelResolver) {
        this.receiveRepository = receiveRepository;
        this.receiveItemRepository = receiveItemRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.ledger = ledger;
        this.lotTraceRepository = lotTraceRepository;
        this.stockRepository = stockRepository;
        this.auditLogService = auditLogService;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
        this.supplierRepository = supplierRepository;
        this.arApService = arApService;
        this.dateValidator = dateValidator;
        this.approvalRecordRepository = approvalRecordRepository;
        this.approvalLevelResolver = approvalLevelResolver;
    }

    /** 已校验的建单参数（create / update 共用）。 */
    private record Prepared(PurchaseOrder order, Long locationId,
                            List<PurchaseReceiveItemRequest> items, BigDecimal total) {
    }

    /**
     * 建单：只写单据头 + 明细，状态 DRAFT，不扣/加库存、不生成应付。
     * 订单状态、温控、效期、检验单号等前置校验在此时完成（提交/审批执行时不再重复合规校验）。
     */
    @Transactional
    public PurchaseReceiveVO create(PurchaseReceiveCreateRequest req) {
        Prepared prepared = prepare(req);
        PurchaseReceive receive = new PurchaseReceive();
        receive.setReceiveNo(ledger.genNo("RC"));
        receive.setOrderId(prepared.order().getId());
        receive.setReceiveDate(req.receiveDate() == null ? LocalDate.now() : req.receiveDate());
        receive.setStatus("DRAFT");
        receive.setTotalAmount(prepared.total());
        receive.setWarehouseId(prepared.locationId());
        receive.setCreatedBy(ledger.currentUser());
        receive = receiveRepository.save(receive);

        List<PurchaseReceiveItem> saved = saveItems(receive.getId(), prepared.items());
        Map<Long, String> names = productNames(saved.stream().map(PurchaseReceiveItem::getProductId).toList());

        auditLogService.log(receive.getCreatedBy(), "CREATE_RECEIVE", BIZ_TYPE, receive.getId(),
                "创建采购入库单 " + receive.getReceiveNo() + "（待提交），金额 " + receive.getTotalAmount().toPlainString(),
                null, AuditLogService.json("receiveNo", receive.getReceiveNo(), "status", "DRAFT",
                        "totalAmount", receive.getTotalAmount(), "orderId", receive.getOrderId()));
        return toVO(receive, saved.stream().map(i -> toItemVO(i, names)).toList(),
                prepared.order().getOrderNo(), supplierName(prepared.order().getSupplierId()));
    }

    /** 编辑：仅草稿或已驳回可改，整单重建明细；已驳回改完仍为 REJECTED，需重新提交。 */
    @Transactional
    public PurchaseReceiveVO update(Long id, PurchaseReceiveCreateRequest req) {
        PurchaseReceive receive = receiveRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("采购入库单不存在: " + id));
        if (!"DRAFT".equals(receive.getStatus()) && !"REJECTED".equals(receive.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回入库单可编辑，当前状态: " + receive.getStatus());
        }
        Prepared prepared = prepare(req);
        receive.setOrderId(prepared.order().getId());
        if (req.receiveDate() != null) {
            receive.setReceiveDate(req.receiveDate());
        }
        receive.setTotalAmount(prepared.total());
        receive.setWarehouseId(prepared.locationId());
        receiveRepository.save(receive);

        receiveItemRepository.deleteAll(receiveItemRepository.findByReceiveId(id));
        List<PurchaseReceiveItem> saved = saveItems(id, prepared.items());

        Map<Long, String> names = productNames(saved.stream().map(PurchaseReceiveItem::getProductId).toList());
        auditLogService.log(ledger.currentUser(), "UPDATE_RECEIVE", BIZ_TYPE, id,
                "编辑采购入库单 " + receive.getReceiveNo() + "，金额 " + receive.getTotalAmount().toPlainString(),
                null, AuditLogService.json("receiveNo", receive.getReceiveNo(), "status", receive.getStatus(),
                        "totalAmount", receive.getTotalAmount(), "itemCount", saved.size()));
        return toVO(receive, saved.stream().map(i -> toItemVO(i, names)).toList(),
                prepared.order().getOrderNo(), supplierName(prepared.order().getSupplierId()));
    }

    /** 提审：DRAFT / REJECTED -> SUBMITTED，写 PENDING approval_record（金额分级节点）。 */
    @Transactional
    public PurchaseReceiveVO submit(Long id) {
        // 单据头悲观锁：防止并发重复提交产生多条审批记录
        PurchaseReceive receive = receiveRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购入库单不存在: " + id));
        if (!"DRAFT".equals(receive.getStatus()) && !"REJECTED".equals(receive.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回入库单可提交，当前状态: " + receive.getStatus());
        }
        if (receiveItemRepository.findByReceiveId(id).isEmpty()) {
            throw BusinessException.badRequest("入库明细不能为空");
        }
        String before = receive.getStatus();
        receive.setStatus("SUBMITTED");
        receiveRepository.save(receive);

        ApprovalRecord record = new ApprovalRecord();
        record.setBizType(BIZ_TYPE);
        record.setBizId(receive.getId());
        record.setNode(approvalLevelResolver.node(BIZ_TYPE, receive.getTotalAmount()));
        record.setStatus("PENDING");
        approvalRecordRepository.save(record);

        auditLogService.log(ledger.currentUser(), "SUBMIT_RECEIVE", BIZ_TYPE, receive.getId(),
                "提交采购入库单 " + receive.getReceiveNo() + "，金额 " + receive.getTotalAmount().toPlainString(),
                AuditLogService.json("receiveNo", receive.getReceiveNo(), "status", before),
                AuditLogService.json("receiveNo", receive.getReceiveNo(), "status", "SUBMITTED",
                        "node", record.getNode()));
        return get(id);
    }

    /** 审批驳回：SUBMITTED -> REJECTED（可编辑后重新提交）。 */
    @Transactional
    public void reject(Long id) {
        // 单据头悲观锁：防止并发驳回/审批交错导致状态回退
        PurchaseReceive receive = receiveRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购入库单不存在: " + id));
        if (!"SUBMITTED".equals(receive.getStatus())) {
            throw BusinessException.badRequest("仅已提交状态可驳回，当前状态: " + receive.getStatus());
        }
        receive.setStatus("REJECTED");
        receiveRepository.save(receive);
    }

    /** 审批上下文（状态守卫 / 回避校验 / 金额分级）。 */
    public ApprovalTarget approvalTarget(Long id) {
        PurchaseReceive receive = receiveRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("采购入库单不存在: " + id));
        return new ApprovalTarget(receive.getReceiveNo(), receive.getStatus(), receive.getCreatedBy(),
                receive.getTotalAmount());
    }

    /**
     * 审批通过：SUBMITTED -> APPROVED -> PUSHED（下发 WMS），仅状态推进，不动库存与财务。
     * wms_pushed_at 记录下发时间，等待 WMS 回传实量后执行入库。
     */
    @Transactional
    public void approveDocument(Long id) {
        // 单据头悲观锁：防止并发重复审批导致重复下发 WMS
        PurchaseReceive receive = receiveRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购入库单不存在: " + id));
        if (!"SUBMITTED".equals(receive.getStatus())) {
            throw BusinessException.badRequest("仅已提交状态可审批，当前状态: " + receive.getStatus());
        }
        receive.setStatus("APPROVED");
        receiveRepository.save(receive);
        receive.setStatus("PUSHED");
        receive.setWmsPushedAt(java.time.LocalDateTime.now());
        receiveRepository.save(receive);

        auditLogService.log(ledger.currentUser(), "RECEIVE_PUSHED", BIZ_TYPE, receive.getId(),
                "采购入库单 " + receive.getReceiveNo() + " 审批通过并下发 WMS，金额 "
                        + receive.getTotalAmount().toPlainString(),
                AuditLogService.json("receiveNo", receive.getReceiveNo(), "status", "SUBMITTED"),
                AuditLogService.json("receiveNo", receive.getReceiveNo(), "status", "PUSHED",
                        "totalAmount", receive.getTotalAmount()));
    }

    /**
     * WMS 回传（批次十四核心）：仅 PUSHED 可回传；按实量执行入库并置 COMPLETED。
     * 重复回传（已 COMPLETED）返回 400，不重复入库。
     */
    @Transactional
    public PurchaseReceiveVO wmsCallback(Long id, com.pharma.common.WmsCallbackRequest req) {
        // 单据头悲观锁：防止 WMS 并发重复回传导致重复入库
        PurchaseReceive receive = receiveRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购入库单不存在: " + id));
        if (!"PUSHED".equals(receive.getStatus())) {
            throw BusinessException.badRequest("仅已下发 WMS 的单据可回传实量，当前状态: " + receive.getStatus());
        }
        Map<Long, BigDecimal> actual = resolveActualQty(receiveItemRepository.findByReceiveId(id), id, req);
        receive.setWmsNo(req == null ? null : req.wmsNo());
        receive.setWmsCallbackAt(req == null || req.callbackTime() == null
                ? java.time.LocalDateTime.now() : req.callbackTime());
        receiveRepository.save(receive);

        executeActual(id, actual);
        return get(id);
    }

    /**
     * 按 WMS 实量执行入库：actualQty 为 0 的行跳过；每行写 actual_qty / qty_diff 差异留痕。
     * 库存与应付按实量计算，执行完置 COMPLETED。
     */
    @Transactional
    public PurchaseReceiveVO executeActual(Long id, Map<Long, BigDecimal> actualQtyByItemId) {
        // 单据头悲观锁：防止并发执行；wmsCallback 已锁同一行时本事务内重复锁读无副作用
        PurchaseReceive receive = receiveRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购入库单不存在: " + id));
        if (!"PUSHED".equals(receive.getStatus())) {
            throw BusinessException.badRequest("仅已下发 WMS 的单据可执行入库，当前状态: " + receive.getStatus());
        }
        PurchaseOrder order = orderRepository.findById(receive.getOrderId())
                .orElseThrow(() -> BusinessException.notFound("采购订单不存在: " + receive.getOrderId()));
        List<PurchaseReceiveItem> items = receiveItemRepository.findByReceiveId(id);
        if (items.isEmpty()) {
            throw BusinessException.badRequest("入库明细不能为空");
        }
        Long locationId = receive.getWarehouseId();
        if (locationId == null) {
            throw BusinessException.badRequest("入库仓库缺失，无法执行入库: " + receive.getReceiveNo());
        }
        // P1-D 执行前合规复核：必须在任何库存/成本/应付写入之前按当前供应商/商品主数据复核
        validateExecutionCompliance(order, items);

        String opUser = ledger.currentUser();
        Map<Long, InventoryLedgerService.Balance> balances = new HashMap<>();
        BigDecimal executedTotal = BigDecimal.ZERO;
        for (PurchaseReceiveItem ri : items) {
            BigDecimal actual = actualQtyByItemId == null || actualQtyByItemId.get(ri.getId()) == null
                    ? ri.getQuantity() : actualQtyByItemId.get(ri.getId());
            ri.setActualQty(actual);
            ri.setQtyDiff(actual.subtract(ri.getQuantity()));
            receiveItemRepository.save(ri);
            // 实量为 0：该行未实际到货，不出库存与应付
            if (actual.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal unitCost = ri.getUnitCost();
            executedTotal = executedTotal.add(actual.multiply(unitCost));

            Stock stock = ledger.upsertStock(ri.getProductId(), ri.getBatchNo(), ri.getProduceDate(),
                    ri.getExpireDate(), locationId, actual, unitCost);

            BigDecimal afterQty = stock.getQuantity();
            BigDecimal beforeQty = afterQty.subtract(actual);
            ledger.writeStockTransaction(ri.getProductId(), ri.getBatchNo(), OP_IN, REF_PURCHASE_RECEIVE,
                    receive.getId(), locationId, actual, beforeQty, afterQty, opUser);

            ledger.writeLotTrace(ri.getProductId(), ri.getBatchNo(), TRACE_PURCHASE_RECEIVE_ITEM, ri.getId(),
                    TRACE_STOCK, stock.getId(), actual, opUser);

            InventoryLedgerService.Balance current = balances.computeIfAbsent(ri.getProductId(), ledger::currentBalance);
            balances.put(ri.getProductId(), ledger.applyCostIn(ri.getProductId(), ri.getBatchNo(), actual, unitCost, current));
        }

        BigDecimal beforeTotal = receive.getTotalAmount();
        receive.setTotalAmount(executedTotal.setScale(2, java.math.RoundingMode.HALF_UP));
        // 先落 COMPLETED 再重算订单状态：重算只统计已执行（COMPLETED/ACTIVE）的入库单
        receive.setStatus("COMPLETED");
        receiveRepository.save(receive);

        advanceOrderStatus(order);

        arApService.generateAp(order.getSupplierId(), REF_PURCHASE_RECEIVE, receive.getId(),
                receive.getTotalAmount());

        auditLogService.log(opUser, "RECEIVE_COMPLETED", BIZ_TYPE, receive.getId(),
                "采购入库单 " + receive.getReceiveNo() + " WMS 回传完成，实收金额 "
                        + receive.getTotalAmount().toPlainString() + "（单据预估 " + beforeTotal.toPlainString() + "）",
                AuditLogService.json("receiveNo", receive.getReceiveNo(), "status", "PUSHED",
                        "totalAmount", beforeTotal),
                AuditLogService.json("receiveNo", receive.getReceiveNo(), "status", "COMPLETED",
                        "totalAmount", receive.getTotalAmount(), "wmsNo", receive.getWmsNo(),
                        "itemCount", items.size()));

        Map<Long, String> names = productNames(items.stream().map(PurchaseReceiveItem::getProductId).toList());
        return toVO(receive, items.stream().map(i -> toItemVO(i, names)).toList(),
                order.getOrderNo(), supplierName(order.getSupplierId()));
    }

    /**
     * WMS 执行前合规复核（P1-D）：建单/审批时已校验供应商与商品资质，但到 WMS 回传执行之间
     * 证照可能过期、首营/商品状态可能变化，执行入库前必须按当前主数据重新复核，
     * 失败抛 400 且校验先于库存/成本/应付写入（事务回滚，不落库存）。
     *
     * @param order 关联采购订单（供应商来源）
     * @param items 待执行入库明细（逐行复核商品）
     */
    private void validateExecutionCompliance(PurchaseOrder order, List<PurchaseReceiveItem> items) {
        Supplier supplier = supplierRepository.findById(order.getSupplierId())
                .orElseThrow(() -> BusinessException.badRequest("供应商不存在: " + order.getSupplierId()));
        // R3 首营阻断：供应商当前仍须为已审核通过
        if (!"APPROVED".equals(supplier.getFirstAuditStatus())) {
            throw BusinessException.badRequest("供应商未过首营审核，不可执行采购入库: " + supplier.getName());
        }
        // R4 证照效期锁交易：证照早于今天视为已过期
        if (supplier.getLicenseExpire() != null && supplier.getLicenseExpire().isBefore(LocalDate.now())) {
            throw BusinessException.badRequest("供应商证照已过期，不可执行采购入库: " + supplier.getName());
        }
        Map<Long, Product> productCache = new HashMap<>();
        for (PurchaseReceiveItem item : items) {
            validateProductForExecution(item.getProductId(), productCache);
        }
    }

    /**
     * 执行前逐明细复核商品：必须存在、启用（status=ACTIVE）；已维护首营状态时也必须为 APPROVED。
     *
     * @param productId 商品主键
     * @param cache     本次执行内的商品缓存（同一商品多批次只查一次库）
     */
    private void validateProductForExecution(Long productId, Map<Long, Product> cache) {
        Product product = cache.computeIfAbsent(productId, pid -> productRepository.findById(pid).orElse(null));
        if (product == null) {
            throw BusinessException.badRequest("商品不存在: " + productId);
        }
        if (!"ACTIVE".equals(product.getStatus())) {
            throw BusinessException.badRequest("商品非启用状态，不可执行采购入库: " + productLabel(product));
        }
        // 历史数据可能未维护首营状态（null）：有值时按 APPROVED 复核
        if (product.getFirstAuditStatus() != null && !"APPROVED".equals(product.getFirstAuditStatus())) {
            throw BusinessException.badRequest("商品未过首营审核，不可执行采购入库: " + productLabel(product));
        }
    }

    /** 商品展示名：优先通用名，缺失时回退编码/主键，保证错误文案含可识别商品信息。 */
    private static String productLabel(Product product) {
        if (product.getGenericName() != null && !product.getGenericName().isBlank()) {
            return product.getGenericName();
        }
        return product.getCode() == null ? String.valueOf(product.getId()) : product.getCode();
    }

    /** 回传实量解析：未提供明细则全部按单据量；提供则逐行校验归属与非负。 */
    private Map<Long, BigDecimal> resolveActualQty(List<PurchaseReceiveItem> items, Long id,
                                                   com.pharma.common.WmsCallbackRequest req) {
        Map<Long, BigDecimal> actual = new HashMap<>();
        if (req == null || req.items() == null || req.items().isEmpty()) {
            for (PurchaseReceiveItem it : items) {
                actual.put(it.getId(), it.getQuantity());
            }
            return actual;
        }
        Map<Long, PurchaseReceiveItem> byId = new HashMap<>();
        for (PurchaseReceiveItem it : items) {
            byId.put(it.getId(), it);
        }
        for (com.pharma.common.WmsCallbackRequest.Item it : req.items()) {
            if (it == null || it.itemId() == null) {
                throw BusinessException.badRequest("回传明细 itemId 不能为空");
            }
            if (!byId.containsKey(it.itemId())) {
                throw BusinessException.badRequest("回传明细不属于该入库单: itemId=" + it.itemId());
            }
            BigDecimal qty = it.actualQty() == null ? BigDecimal.ZERO : it.actualQty();
            if (qty.compareTo(BigDecimal.ZERO) < 0) {
                throw BusinessException.badRequest("回传实际数量不能为负: itemId=" + it.itemId());
            }
            actual.put(it.itemId(), qty);
        }
        // 未在回传中出现的行按单据量处理
        for (PurchaseReceiveItem it : items) {
            actual.putIfAbsent(it.getId(), it.getQuantity());
        }
        return actual;
    }

    /** 入库仓库记录在单据头（明细无 warehouse 列）：建单时持久化，WMS 回传执行时取用。 */
    public PageResult<PurchaseReceiveVO> list(String keyword, int page, int size) {
        return list(keyword, null, page, size);
    }

    /** 列表（批次十四：支持按状态过滤，供前端「待提交/待审批/已完成」页签）。 */
    public PageResult<PurchaseReceiveVO> list(String keyword, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<PurchaseReceive> result = receiveRepository.search(
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                status == null || status.isBlank() ? null : status.trim(), pageable);

        List<Long> ids = result.getContent().stream().map(PurchaseReceive::getId).toList();
        Map<Long, List<PurchaseReceiveItem>> itemMap = receiveItemRepository.findByReceiveIdIn(ids).stream()
                .collect(Collectors.groupingBy(PurchaseReceiveItem::getReceiveId));

        Map<Long, PurchaseOrder> orders = orderRepository.findAllById(result.getContent().stream()
                        .map(PurchaseReceive::getOrderId).toList()).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, o -> o));
        Map<Long, String> supplierNames = supplierNames(orders.values().stream()
                .map(PurchaseOrder::getSupplierId).toList());
        Map<Long, String> productNames = productNames(itemMap.values().stream().flatMap(List::stream)
                .map(PurchaseReceiveItem::getProductId).toList());

        List<PurchaseReceiveVO> records = result.getContent().stream()
                .map(r -> {
                    PurchaseOrder order = orders.get(r.getOrderId());
                    return toVO(r, itemMap.getOrDefault(r.getId(), List.of()).stream()
                                    .map(i -> toItemVO(i, productNames)).toList(),
                            order == null ? null : order.getOrderNo(),
                            order == null ? null : supplierNames.get(order.getSupplierId()));
                })
                .toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    /** 入库单详情：含明细、关联订单号与供应商名；不存在返回 404（BusinessException.notFound）。 */
    public PurchaseReceiveVO get(Long id) {
        PurchaseReceive receive = receiveRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("采购入库单不存在: " + id));
        PurchaseOrder order = orderRepository.findById(receive.getOrderId()).orElse(null);
        List<PurchaseReceiveItem> items = receiveItemRepository.findByReceiveId(id);
        Map<Long, String> names = productNames(items.stream().map(PurchaseReceiveItem::getProductId).toList());
        return toVO(receive, items.stream().map(i -> toItemVO(i, names)).toList(),
                order == null ? null : order.getOrderNo(),
                order == null ? null : supplierName(order.getSupplierId()));
    }

    /**
     * 导出用全量查询（上限 {@link ExportWriter#MAX_ROWS}）：复用列表的查询条件，不分页。
     * 金额为入库成本合计，无库存成本查看权限时留空（成本脱敏）。
     */
    public ExportPayload<PurchaseReceiveExportRow> exportRows(String keyword) {
        return exportRows(keyword, null);
    }

    public ExportPayload<PurchaseReceiveExportRow> exportRows(String keyword, String status) {
        Pageable pageable = PageRequest.of(0, ExportWriter.MAX_ROWS, Sort.by(Sort.Direction.DESC, "id"));
        Page<PurchaseReceive> result = receiveRepository.search(
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                status == null || status.isBlank() ? null : status.trim(), pageable);
        List<PurchaseReceive> content = result.getContent();
        List<Long> ids = content.stream().map(PurchaseReceive::getId).toList();
        Map<Long, List<PurchaseReceiveItem>> itemMap = receiveItemRepository.findByReceiveIdIn(ids).stream()
                .collect(Collectors.groupingBy(PurchaseReceiveItem::getReceiveId));
        Map<Long, PurchaseOrder> orders = orderRepository.findAllById(content.stream()
                        .map(PurchaseReceive::getOrderId).toList()).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, o -> o));
        Map<Long, String> supplierNames = supplierNames(orders.values().stream()
                .map(PurchaseOrder::getSupplierId).toList());
        boolean costVisible = DataScope.canViewCost();
        List<PurchaseReceiveExportRow> rows = content.stream()
                .map(r -> {
                    PurchaseOrder order = orders.get(r.getOrderId());
                    return new PurchaseReceiveExportRow(r.getReceiveNo(), billDate(r.getReceiveDate()),
                            order == null ? null : supplierNames.get(order.getSupplierId()),
                            costVisible ? receiveAmount(itemMap.getOrDefault(r.getId(), List.of())) : null,
                            r.getStatus(), r.getCreatedBy());
                })
                .toList();
        return new ExportPayload<>(rows, result.getTotalElements(), ExportWriter.MAX_ROWS);
    }

    /** 入库成本合计：Σ 执行实量 × 入库单价（实量口径，未回传时回退单据量）。 */
    private static BigDecimal receiveAmount(List<PurchaseReceiveItem> items) {
        return items.stream().map(i -> executedQty(i).multiply(i.getUnitCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static String billDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    /**
     * R8 作废红冲：COMPLETED（含历史 ACTIVE）→ 反向库存流水 + 反向成本台账 + lot_trace 作废记录 + 应付联动作废；
     * DRAFT / REJECTED → 仅置 VOID（未执行入库，无库存/财务影响）；SUBMITTED → 拒绝，需先驳回或完成审批。
     * reason 写入审计日志留痕。
     */
    @Transactional
    public PurchaseReceiveVO voidReceive(Long id, String reason) {
        // 单据头悲观锁：防止作废与执行/回传并发交错
        PurchaseReceive receive = receiveRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购入库单不存在: " + id));
        PurchaseOrder order = orderRepository.findById(receive.getOrderId()).orElse(null);
        // IDOR 防护：入库单制单人或关联采购单制单人（特权身份豁免）
        DataScope.requireAnyOwnership(receive.getCreatedBy(), order == null ? null : order.getCreatedBy());
        if ("VOID".equals(receive.getStatus())) {
            throw BusinessException.badRequest("入库单已作废: " + receive.getReceiveNo());
        }
        if ("SUBMITTED".equals(receive.getStatus())) {
            throw BusinessException.badRequest("待审批入库单不可直接作废，请先驳回或完成审批: "
                    + receive.getReceiveNo());
        }
        if ("PUSHED".equals(receive.getStatus())) {
            throw BusinessException.badRequest("已下发 WMS 的入库单不可直接作废，请等待回传完成后再处理: "
                    + receive.getReceiveNo());
        }

        String before = receive.getStatus();
        String opUser = ledger.currentUser();
        List<PurchaseReceiveItem> items = receiveItemRepository.findByReceiveId(id);
        boolean executed = "COMPLETED".equals(before) || "ACTIVE".equals(before);
        // 先置 VOID 再重算订单状态：重算只统计已执行的入库单，避免把本单仍计入
        receive.setStatus("VOID");
        receiveRepository.save(receive);
        if (executed) {
            for (PurchaseReceiveItem ri : items) {
                // 实量口径：只冲回实际到货数量；实量为 0 的行执行时未落库存/追溯，无需冲回
                BigDecimal qty = executedQty(ri);
                if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                Long stockId = lotTraceRepository
                        .findFirstByProductIdAndBatchNoAndSourceTypeAndSourceIdAndTargetType(
                                ri.getProductId(), ri.getBatchNo(), TRACE_PURCHASE_RECEIVE_ITEM, ri.getId(), TRACE_STOCK)
                        .map(t -> t.getTargetId())
                        .orElseThrow(() -> BusinessException.badRequest(
                                "未找到入库追溯，无法作废: item=" + ri.getId()));
                // 库存行悲观锁：作废冲回前按 (商品, 批号, 仓库) 锁读，防止与并发出入库交错导致超扣
                Stock stock = lockStockById(stockId);
                BigDecimal beforeQty = stock.getQuantity();
                if (beforeQty.compareTo(qty) < 0) {
                    throw BusinessException.badRequest(
                            "库存不足，无法作废入库: 批次 " + ri.getBatchNo() + " 现存 " + beforeQty.toPlainString()
                                    + " < " + qty.toPlainString());
                }
                BigDecimal after = beforeQty.subtract(qty);
                stock.setQuantity(after);
                stockRepository.save(stock);

                ledger.writeStockTransaction(ri.getProductId(), ri.getBatchNo(), OP_OUT, REF_PURCHASE_RECEIVE,
                        receive.getId(), stock.getLocationId(), qty.negate(), beforeQty, after, opUser);

                ledger.applyCostOut(ri.getProductId(), ri.getBatchNo(), qty, ri.getUnitCost(),
                        ledger.currentBalance(ri.getProductId()));

                ledger.writeLotTrace(ri.getProductId(), ri.getBatchNo(), TRACE_STOCK, stockId,
                        TRACE_PURCHASE_RECEIVE_VOID, receive.getId(), qty, opUser);
            }

            // 财务联动：应付单据联动作废
            arApService.voidByBiz(REF_PURCHASE_RECEIVE, receive.getId());

            // P1-19：作废后按有效入库单重算订单状态（可能由 PARTIAL_RECEIVED 回退到 APPROVED）
            if (order != null) {
                advanceOrderStatus(order);
            }
        }

        auditLogService.log(opUser, "VOID_RECEIVE", REF_PURCHASE_RECEIVE, receive.getId(),
                "作废采购入库单 " + receive.getReceiveNo() + (reason == null || reason.isBlank()
                        ? "" : "，原因：" + reason.trim()),
                AuditLogService.json("receiveNo", receive.getReceiveNo(), "status", before,
                        "itemCount", items.size(), "totalQuantity", totalQuantity(items)),
                AuditLogService.json("receiveNo", receive.getReceiveNo(), "status", "VOID",
                        "itemCount", items.size(), "totalQuantity", totalQuantity(items), "reason", reason));

        Map<Long, String> names = productNames(items.stream()
                .map(PurchaseReceiveItem::getProductId).toList());
        List<PurchaseReceiveItemVO> vos = items.stream().map(i -> toItemVO(i, names)).toList();
        return toVO(receive, vos,
                order == null ? null : order.getOrderNo(),
                order == null ? null : supplierName(order.getSupplierId()));
    }

    /** 建单/编辑共用校验：订单状态、业务日期、明细与合规（效期/检验单号/温控/同批效期）。 */
    private Prepared prepare(PurchaseReceiveCreateRequest req) {
        if (req.orderId() == null) {
            throw BusinessException.badRequest("采购订单不能为空");
        }
        dateValidator.validateBizDate(req.receiveDate(), "入库日期");
        PurchaseOrder order = orderRepository.findById(req.orderId())
                .orElseThrow(() -> BusinessException.notFound("采购订单不存在: " + req.orderId()));
        String orderStatus = order.getStatus();
        if ("REJECTED".equals(orderStatus)) {
            throw BusinessException.badRequest("订单已驳回，不可入库");
        }
        if ("SUBMITTED".equals(orderStatus)) {
            throw BusinessException.badRequest("订单待审批（SUBMITTED），审核通过后方可入库");
        }
        if (!"APPROVED".equals(orderStatus) && !"PARTIAL_RECEIVED".equals(orderStatus)) {
            throw BusinessException.badRequest(
                    "订单状态 " + orderStatus + " 不可入库，需为 APPROVED / PARTIAL_RECEIVED");
        }

        List<PurchaseReceiveItemRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("入库明细不能为空");
        }

        if (req.warehouseId() == null) {
            throw BusinessException.badRequest("入库仓库不能为空");
        }
        Long locationId = ledger.requireLocationId(req.warehouseId());
        Warehouse warehouse = warehouseRepository.findById(locationId)
                .orElseThrow(() -> BusinessException.badRequest("仓库不存在: " + req.warehouseId()));

        Map<Long, BigDecimal> orderQty = orderItemRepository.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getProductId, PurchaseOrderItem::getQuantity,
                        BigDecimal::add));
        LocalDate today = LocalDate.now();
        Map<Long, Product> productCache = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseReceiveItemRequest it : items) {
            if (it.productId() == null) {
                throw BusinessException.badRequest("商品不能为空");
            }
            if (!orderQty.containsKey(it.productId())) {
                throw BusinessException.badRequest("商品不在采购订单中: " + it.productId());
            }
            if (it.quantity() == null || it.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("入库数量必须大于0");
            }
            if (it.batchNo() == null || it.batchNo().isBlank()) {
                throw BusinessException.badRequest("批号不能为空");
            }
            if (it.expireDate() == null) {
                throw BusinessException.badRequest("效期不能为空");
            }
            if (it.unitCost() == null || it.unitCost().compareTo(BigDecimal.ZERO) < 0) {
                throw BusinessException.badRequest("入库单价不能为负");
            }
            validateInboundCompliance(it, warehouse, today,
                    productCache.computeIfAbsent(it.productId(), pid -> productRepository.findById(pid).orElse(null)));
            total = total.add(it.quantity().multiply(it.unitCost()));
        }
        return new Prepared(order, locationId, items, total.setScale(2, RoundingMode.HALF_UP));
    }

    /** 明细落库：只写数量/批号/效期/成本/检验单号，不动库存。 */
    private List<PurchaseReceiveItem> saveItems(Long receiveId, List<PurchaseReceiveItemRequest> items) {
        List<PurchaseReceiveItem> saved = new ArrayList<>();
        for (PurchaseReceiveItemRequest it : items) {
            PurchaseReceiveItem ri = new PurchaseReceiveItem();
            ri.setReceiveId(receiveId);
            ri.setProductId(it.productId());
            ri.setQuantity(it.quantity());
            ri.setBatchNo(it.batchNo());
            ri.setProduceDate(it.produceDate());
            ri.setExpireDate(it.expireDate());
            ri.setUnitCost(it.unitCost());
            ri.setInspectionNo(it.inspectionNo());
            saved.add(receiveItemRepository.save(ri));
        }
        return saved;
    }

    /**
     * 入库合规校验（P1-14/15/16）：
     * 1) 效期晚于生产日期、且晚于今天（拒绝过期/当日到期批次）；
     * 2) 检验报告单号必填（GSP 随货同行检验报告）；
     * 3) 温控兼容：ROOM_TEMP 只入常温仓；COOL 可入阴凉/冷藏；COLD 只入冷藏仓；
     * 4) 同批号效期冲突：同一商品同一批号在库批次效期必须一致，避免同批多效期。
     */
    private void validateInboundCompliance(PurchaseReceiveItemRequest it, Warehouse warehouse,
                                           LocalDate today, Product product) {
        if (it.inspectionNo() == null || it.inspectionNo().isBlank()) {
            throw BusinessException.badRequest("检验报告单号不能为空: 批号 " + it.batchNo());
        }
        if (it.produceDate() != null && !it.expireDate().isAfter(it.produceDate())) {
            throw BusinessException.badRequest("效期必须晚于生产日期: 批号 " + it.batchNo()
                    + " 生产 " + it.produceDate() + "，效期 " + it.expireDate());
        }
        if (!it.expireDate().isAfter(today)) {
            throw BusinessException.badRequest("效期必须晚于今天，不可入库过期批次: 批号 " + it.batchNo()
                    + " 效期 " + it.expireDate());
        }

        if (product != null && product.getStorageCond() != null && warehouse.getTempType() != null) {
            Set<String> allowed = ALLOWED_WAREHOUSE_TEMP.get(product.getStorageCond());
            if (allowed != null && !allowed.contains(warehouse.getTempType())) {
                throw BusinessException.badRequest("温控不兼容：商品 " + product.getCode()
                        + " 存储条件 " + product.getStorageCond() + " 不可入 " + warehouse.getTempType()
                        + " 仓库（" + warehouse.getName() + "）");
            }
        }

        for (Stock stock : stockRepository.findByProductIdAndBatchNo(it.productId(), it.batchNo())) {
            if (!it.expireDate().equals(stock.getExpireDate())) {
                throw BusinessException.badRequest("同批号效期冲突：批号 " + it.batchNo() + " 在库效期 "
                        + stock.getExpireDate() + "，本次效期 " + it.expireDate());
            }
        }
    }

    /**
     * 执行实量口径（统一入口）：优先采用 WMS 回传实量 actualQty；未回传（null）时回退单据量 quantity。
     * 作废库存/成本冲回、订单进度重算、导出金额、审计汇总必须统一走此口径，
     * 避免「部分实量执行后仍按单据量冲回/统计」导致库存与订单进度失真。
     *
     * @param item 入库明细
     * @return 该行实际执行数量（永不为 null）
     */
    static BigDecimal executedQty(PurchaseReceiveItem item) {
        return item.getActualQty() == null ? item.getQuantity() : item.getActualQty();
    }

    /**
     * 库存行悲观锁读：追溯记录只保存库存主键，先按主键定位批次，再用唯一键
     * (productId, batchNo, locationId) 锁读，作为作废冲回的唯一可写快照。
     *
     * @param stockId 库存批次主键
     * @return 已加悲观写锁的库存行
     */
    private Stock lockStockById(Long stockId) {
        Stock located = stockRepository.findById(stockId)
                .orElseThrow(() -> BusinessException.badRequest("库存批次不存在: " + stockId));
        return stockRepository.findByProductIdAndBatchNoAndLocationIdForUpdate(
                        located.getProductId(), located.getBatchNo(), located.getLocationId())
                .orElseThrow(() -> BusinessException.badRequest("库存批次不存在: " + stockId));
    }

    /**
     * 按有效入库单（已执行 COMPLETED / 历史 ACTIVE，排除已作废与未审批）重算订单状态：
     * 无有效入库 -> APPROVED，收满 -> COMPLETED，否则 PARTIAL_RECEIVED。
     * 收货量按实量口径 {@link #executedQty(PurchaseReceiveItem)} 累计（部分实量不得算作收满）。
     */
    private void advanceOrderStatus(PurchaseOrder order) {
        Map<Long, BigDecimal> orderedQty = orderItemRepository.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getProductId, PurchaseOrderItem::getQuantity,
                        BigDecimal::add));

        List<Long> receiveIds = receiveRepository.findByOrderId(order.getId()).stream()
                .filter(r -> "COMPLETED".equals(r.getStatus()) || "ACTIVE".equals(r.getStatus()))
                .map(PurchaseReceive::getId).toList();
        Map<Long, BigDecimal> receivedQty = new HashMap<>();
        for (PurchaseReceiveItem ri : receiveItemRepository.findByReceiveIdIn(receiveIds)) {
            // 实量口径：部分到货只累计 actualQty，不能按单据量把订单算成收满
            receivedQty.merge(ri.getProductId(), executedQty(ri), BigDecimal::add);
        }

        BigDecimal totalReceived = receivedQty.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalReceived.compareTo(BigDecimal.ZERO) <= 0) {
            order.setStatus("APPROVED");
        } else {
            boolean completed = orderedQty.entrySet().stream().allMatch(e ->
                    receivedQty.getOrDefault(e.getKey(), BigDecimal.ZERO).compareTo(e.getValue()) >= 0);
            order.setStatus(completed ? "COMPLETED" : "PARTIAL_RECEIVED");
        }
        orderRepository.save(order);
    }

    private PurchaseReceiveItemVO toItemVO(PurchaseReceiveItem item, Map<Long, String> productNames) {
        return new PurchaseReceiveItemVO(item.getId(), item.getProductId(),
                productNames.get(item.getProductId()), item.getQuantity(),
                item.getBatchNo(), item.getProduceDate(), item.getExpireDate(), visibleCost(item.getUnitCost()),
                item.getInspectionNo(), item.getActualQty(), item.getQtyDiff());
    }

    /** 成本脱敏（P1-23）：无 inventory:cost:view 权限返回 null。 */
    private BigDecimal visibleCost(BigDecimal unitCost) {
        return DataScope.canViewCost() ? unitCost : null;
    }

    private PurchaseReceiveVO toVO(PurchaseReceive receive, List<PurchaseReceiveItemVO> items,
                                   String orderNo, String supplierName) {
        return new PurchaseReceiveVO(receive.getId(), receive.getReceiveNo(), receive.getOrderId(),
                orderNo, receive.getReceiveDate(), receive.getStatus(), supplierName, receive.getCreatedBy(),
                receive.getCreatedAt(), receive.getWmsNo(), receive.getWmsPushedAt(), receive.getWmsCallbackAt(),
                items);
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

    /** 审计快照用：明细执行实量合计（实量口径，未回传时回退单据量）。 */
    private static BigDecimal totalQuantity(List<PurchaseReceiveItem> items) {
        return items.stream().map(PurchaseReceiveService::executedQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
