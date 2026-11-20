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
import com.pharma.common.DateValidator;
import com.pharma.common.PageResult;
import com.pharma.common.TemperatureCompatibility;
import com.pharma.common.export.ExportPayload;
import com.pharma.common.export.ExportWriter;
import com.pharma.finance.service.ArApService;
import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.repository.StockRepository;
import com.pharma.inventory.service.InventoryLedgerService;
import com.pharma.purchase.dto.*;
import com.pharma.purchase.entity.PurchaseOrder;
import com.pharma.purchase.entity.PurchaseReceive;
import com.pharma.purchase.entity.PurchaseReturn;
import com.pharma.purchase.entity.PurchaseReturnItem;
import com.pharma.purchase.repository.PurchaseOrderRepository;
import com.pharma.purchase.repository.PurchaseReceiveRepository;
import com.pharma.purchase.repository.PurchaseReturnItemRepository;
import com.pharma.purchase.repository.PurchaseReturnRepository;
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

import static com.pharma.inventory.service.InventoryLedgerService.OP_IN;
import static com.pharma.inventory.service.InventoryLedgerService.REF_PURCHASE_RETURN;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_PURCHASE_RETURN_ITEM;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_PURCHASE_RETURN_VOID;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_STOCK;

/**
 * 采购退货（P1-11）：DRAFT（建单只记明细）→ SUBMITTED → APPROVED → 执行 → COMPLETED。
 * 执行内容：指定批次精确扣减库存（OUT 流水 ref_type=PURCHASE_RETURN）+ 按批次原成本生成负数应付（红字）。
 * 审批通过前不动库存与应付（批次十四决策 1，方案 A）。
 */
@Service
public class PurchaseReturnService {

    public static final String BIZ_TYPE = "PURCHASE_RETURN";

    private final PurchaseReturnRepository returnRepository;
    private final PurchaseReturnItemRepository returnItemRepository;
    private final PurchaseReceiveRepository receiveRepository;
    private final PurchaseOrderRepository orderRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockRepository stockRepository;
    private final InventoryLedgerService ledger;
    private final ArApService arApService;
    private final AuditLogService auditLogService;
    private final DateValidator dateValidator;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final ApprovalLevelResolver approvalLevelResolver;

    public PurchaseReturnService(PurchaseReturnRepository returnRepository,
                                 PurchaseReturnItemRepository returnItemRepository,
                                 PurchaseReceiveRepository receiveRepository,
                                 PurchaseOrderRepository orderRepository,
                                 SupplierRepository supplierRepository,
                                 ProductRepository productRepository,
                                 WarehouseRepository warehouseRepository,
                                 StockRepository stockRepository,
                                 InventoryLedgerService ledger,
                                 ArApService arApService,
                                 AuditLogService auditLogService,
                                 DateValidator dateValidator,
                                 ApprovalRecordRepository approvalRecordRepository,
                                 ApprovalLevelResolver approvalLevelResolver) {
        this.returnRepository = returnRepository;
        this.returnItemRepository = returnItemRepository;
        this.receiveRepository = receiveRepository;
        this.orderRepository = orderRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
        this.stockRepository = stockRepository;
        this.ledger = ledger;
        this.arApService = arApService;
        this.auditLogService = auditLogService;
        this.dateValidator = dateValidator;
        this.approvalRecordRepository = approvalRecordRepository;
        this.approvalLevelResolver = approvalLevelResolver;
    }

    /** 已校验的建单参数（create / update 共用）。 */
    private record Prepared(Long supplierId, String receiveNo, List<PurchaseReturnItem> items, BigDecimal total) {
    }

    /**
     * 建单：只写单据头 + 明细（含按当前批次成本预估的退货金额），状态 DRAFT。
     * 校验批次存在 / 库存充足 / 温控兼容，但不在此时扣减库存。
     */
    @Transactional
    public PurchaseReturnVO create(PurchaseReturnCreateRequest req) {
        Prepared prepared = prepare(req);

        PurchaseReturn header = new PurchaseReturn();
        header.setReturnNo(ledger.genNo("PR"));
        header.setReceiveId(req.receiveId());
        header.setSupplierId(prepared.supplierId());
        header.setReturnDate(req.returnDate() == null ? LocalDate.now() : req.returnDate());
        header.setStatus("DRAFT");
        header.setTotalAmount(prepared.total());
        header.setReason(req.reason());
        header.setCreatedBy(ledger.currentUser());
        header = returnRepository.save(header);

        List<PurchaseReturnItem> saved = saveItems(header.getId(), prepared.items());

        auditLogService.log(header.getCreatedBy(), "CREATE_PURCHASE_RETURN", BIZ_TYPE, header.getId(),
                "创建采购退货单 " + header.getReturnNo() + "（待提交），金额 " + header.getTotalAmount().toPlainString(),
                null, AuditLogService.json("returnNo", header.getReturnNo(), "status", "DRAFT",
                        "totalAmount", header.getTotalAmount(), "itemCount", saved.size()));

        return toVO(header, saved, prepared.receiveNo(), supplierName(prepared.supplierId()));
    }

    /** 编辑：仅草稿或已驳回可改，整单重建明细；已驳回改完仍为 REJECTED，需重新提交。 */
    @Transactional
    public PurchaseReturnVO update(Long id, PurchaseReturnCreateRequest req) {
        PurchaseReturn header = returnRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("采购退货单不存在: " + id));
        if (!"DRAFT".equals(header.getStatus()) && !"REJECTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回退货单可编辑，当前状态: " + header.getStatus());
        }
        Prepared prepared = prepare(req);
        header.setReceiveId(req.receiveId());
        header.setSupplierId(prepared.supplierId());
        if (req.returnDate() != null) {
            header.setReturnDate(req.returnDate());
        }
        header.setTotalAmount(prepared.total());
        header.setReason(req.reason());
        returnRepository.save(header);

        returnItemRepository.deleteAll(returnItemRepository.findByReturnId(id));
        List<PurchaseReturnItem> saved = saveItems(id, prepared.items());

        auditLogService.log(ledger.currentUser(), "UPDATE_PURCHASE_RETURN", BIZ_TYPE, id,
                "编辑采购退货单 " + header.getReturnNo() + "，金额 " + header.getTotalAmount().toPlainString(),
                null, AuditLogService.json("returnNo", header.getReturnNo(), "status", header.getStatus(),
                        "totalAmount", header.getTotalAmount(), "itemCount", saved.size()));
        return toVO(header, saved, prepared.receiveNo(), supplierName(prepared.supplierId()));
    }

    /** 提审：DRAFT / REJECTED -> SUBMITTED，写 PENDING approval_record（金额分级节点）。 */
    @Transactional
    public PurchaseReturnVO submit(Long id) {
        // 单据头悲观锁：防止并发重复提交产生多条审批记录
        PurchaseReturn header = returnRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购退货单不存在: " + id));
        if (!"DRAFT".equals(header.getStatus()) && !"REJECTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回退货单可提交，当前状态: " + header.getStatus());
        }
        if (returnItemRepository.findByReturnId(id).isEmpty()) {
            throw BusinessException.badRequest("退货明细不能为空");
        }
        String before = header.getStatus();
        header.setStatus("SUBMITTED");
        returnRepository.save(header);

        ApprovalRecord record = new ApprovalRecord();
        record.setBizType(BIZ_TYPE);
        record.setBizId(header.getId());
        record.setNode(approvalLevelResolver.node(BIZ_TYPE, header.getTotalAmount()));
        record.setStatus("PENDING");
        approvalRecordRepository.save(record);

        auditLogService.log(ledger.currentUser(), "SUBMIT_PURCHASE_RETURN", BIZ_TYPE, header.getId(),
                "提交采购退货单 " + header.getReturnNo() + "，金额 " + header.getTotalAmount().toPlainString(),
                AuditLogService.json("returnNo", header.getReturnNo(), "status", before),
                AuditLogService.json("returnNo", header.getReturnNo(), "status", "SUBMITTED",
                        "node", record.getNode()));
        return get(id);
    }

    /** 审批驳回：SUBMITTED -> REJECTED（可编辑后重新提交）。 */
    @Transactional
    public void reject(Long id) {
        // 单据头悲观锁：防止并发驳回/审批交错导致状态回退
        PurchaseReturn header = returnRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购退货单不存在: " + id));
        if (!"SUBMITTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅已提交状态可驳回，当前状态: " + header.getStatus());
        }
        header.setStatus("REJECTED");
        returnRepository.save(header);
    }

    /** 审批上下文（状态守卫 / 回避校验 / 金额分级）。 */
    public ApprovalTarget approvalTarget(Long id) {
        PurchaseReturn header = returnRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("采购退货单不存在: " + id));
        return new ApprovalTarget(header.getReturnNo(), header.getStatus(), header.getCreatedBy(),
                header.getTotalAmount());
    }

    /**
     * 审批通过：SUBMITTED -> APPROVED -> PUSHED（下发 WMS），仅状态推进，不扣库存、不出红字应付。
     */
    @Transactional
    public void approveDocument(Long id) {
        // 单据头悲观锁：防止并发重复审批导致重复下发 WMS
        PurchaseReturn header = returnRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购退货单不存在: " + id));
        if (!"SUBMITTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅已提交状态可审批，当前状态: " + header.getStatus());
        }
        header.setStatus("APPROVED");
        returnRepository.save(header);
        header.setStatus("PUSHED");
        header.setWmsPushedAt(java.time.LocalDateTime.now());
        returnRepository.save(header);

        auditLogService.log(ledger.currentUser(), "PURCHASE_RETURN_PUSHED", BIZ_TYPE, header.getId(),
                "采购退货单 " + header.getReturnNo() + " 审批通过并下发 WMS，金额 "
                        + header.getTotalAmount().toPlainString(),
                AuditLogService.json("returnNo", header.getReturnNo(), "status", "SUBMITTED"),
                AuditLogService.json("returnNo", header.getReturnNo(), "status", "PUSHED",
                        "totalAmount", header.getTotalAmount()));
    }

    /**
     * WMS 回传：仅 PUSHED 可回传；按实退量扣减库存并生成红字应付，置 COMPLETED；重复回传 400。
     */
    @Transactional
    public PurchaseReturnVO wmsCallback(Long id, com.pharma.common.WmsCallbackRequest req) {
        // 单据头悲观锁：防止 WMS 并发重复回传导致重复扣减库存
        PurchaseReturn header = returnRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购退货单不存在: " + id));
        if (!"PUSHED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅已下发 WMS 的单据可回传实量，当前状态: " + header.getStatus());
        }
        Map<Long, BigDecimal> actual = resolveActualQty(returnItemRepository.findByReturnId(id), req);
        header.setWmsNo(req == null ? null : req.wmsNo());
        header.setWmsCallbackAt(req == null || req.callbackTime() == null
                ? java.time.LocalDateTime.now() : req.callbackTime());
        returnRepository.save(header);

        executeActual(id, actual);
        return get(id);
    }

    /**
     * 按 WMS 实退量执行退货：actualQty 为 0 的行跳过；每行写 actual_qty / qty_diff；
     * 红字应付按实退金额生成，执行完置 COMPLETED。
     */
    @Transactional
    public PurchaseReturnVO executeActual(Long id, Map<Long, BigDecimal> actualQtyByItemId) {
        // 单据头悲观锁：防止并发执行；wmsCallback 已锁同一行时本事务内重复锁读无副作用
        PurchaseReturn header = returnRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购退货单不存在: " + id));
        if (!"PUSHED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅已下发 WMS 的单据可执行退货，当前状态: " + header.getStatus());
        }
        List<PurchaseReturnItem> items = returnItemRepository.findByReturnId(id);
        if (items.isEmpty()) {
            throw BusinessException.badRequest("退货明细不能为空");
        }
        // P1-D 执行前合规复核：必须在任何库存/成本/应付写入之前按当前供应商/商品主数据复核
        validateExecutionCompliance(header, items);

        String opUser = ledger.currentUser();
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseReturnItem item : items) {
            BigDecimal actual = actualQtyByItemId == null || actualQtyByItemId.get(item.getId()) == null
                    ? item.getQuantity() : actualQtyByItemId.get(item.getId());
            item.setActualQty(actual);
            item.setQtyDiff(actual.subtract(item.getQuantity()));
            if (actual.compareTo(BigDecimal.ZERO) <= 0) {
                returnItemRepository.save(item);
                continue;
            }
            String batchNo = item.getBatchNo();
            BigDecimal unitCost = ledger.outboundBatch(item.getProductId(), batchNo, item.getWarehouseId(),
                    actual, opUser, REF_PURCHASE_RETURN, header.getId());
            item.setUnitCost(unitCost);
            returnItemRepository.save(item);

            stockRepository.findByProductIdAndBatchNoAndLocationId(item.getProductId(), batchNo, item.getWarehouseId())
                    .ifPresent(s -> ledger.writeLotTrace(item.getProductId(), batchNo, TRACE_STOCK,
                            s.getId(), TRACE_PURCHASE_RETURN_ITEM, item.getId(), actual, opUser));

            total = total.add(actual.multiply(unitCost));
        }

        BigDecimal beforeTotal = header.getTotalAmount();
        header.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        header.setStatus("COMPLETED");
        returnRepository.save(header);

        arApService.generateAp(header.getSupplierId(), BIZ_TYPE, header.getId(),
                header.getTotalAmount().negate());

        auditLogService.log(opUser, "PURCHASE_RETURN_COMPLETED", BIZ_TYPE, header.getId(),
                "采购退货单 " + header.getReturnNo() + " WMS 回传完成，实退金额 "
                        + header.getTotalAmount().toPlainString() + "（单据预估 " + beforeTotal.toPlainString() + "）",
                AuditLogService.json("returnNo", header.getReturnNo(), "status", "PUSHED",
                        "totalAmount", beforeTotal),
                AuditLogService.json("returnNo", header.getReturnNo(), "status", "COMPLETED",
                        "totalAmount", header.getTotalAmount(), "wmsNo", header.getWmsNo(),
                        "itemCount", items.size()));

        String receiveNo = header.getReceiveId() == null ? null
                : receiveRepository.findById(header.getReceiveId()).map(PurchaseReceive::getReceiveNo).orElse(null);
        return toVO(header, items, receiveNo, supplierName(header.getSupplierId()));
    }

    /**
     * 执行实量口径（统一入口）：优先采用 WMS 回传实量 actualQty；未回传（null）时回退单据量 quantity。
     * 作废库存/成本回补必须走此口径，避免「部分实退后仍按单据量回补」造成库存虚增。
     *
     * @param item 退货明细
     * @return 该行实际执行数量（永不为 null）
     */
    static BigDecimal executedQty(PurchaseReturnItem item) {
        return item.getActualQty() == null ? item.getQuantity() : item.getActualQty();
    }

    /**
     * WMS 执行前合规复核（P1-D）：建单/审批时已校验供应商资质，但到 WMS 回传执行之间
     * 证照可能过期、供应商首营/商品状态可能变化，执行退货前必须按当前主数据重新复核，
     * 失败抛 400 且校验先于库存/成本/应付写入（事务回滚，不扣库存）。
     *
     * @param header 采购退货单（供应商来源）
     * @param items  待执行退货明细（逐行复核商品）
     */
    private void validateExecutionCompliance(PurchaseReturn header, List<PurchaseReturnItem> items) {
        Supplier supplier = supplierRepository.findById(header.getSupplierId())
                .orElseThrow(() -> BusinessException.badRequest("供应商不存在: " + header.getSupplierId()));
        // R3 首营阻断：供应商当前仍须为已审核通过
        if (!"APPROVED".equals(supplier.getFirstAuditStatus())) {
            throw BusinessException.badRequest("供应商未过首营审核，不可执行采购退货: " + supplier.getName());
        }
        // R4 证照效期锁交易：证照早于今天视为已过期
        if (supplier.getLicenseExpire() != null && supplier.getLicenseExpire().isBefore(LocalDate.now())) {
            throw BusinessException.badRequest("供应商证照已过期，不可执行采购退货: " + supplier.getName());
        }
        Map<Long, Product> productCache = new HashMap<>();
        for (PurchaseReturnItem item : items) {
            Product product = productCache.computeIfAbsent(item.getProductId(),
                    pid -> productRepository.findById(pid).orElse(null));
            if (product == null) {
                throw BusinessException.badRequest("商品不存在: " + item.getProductId());
            }
            if (!"ACTIVE".equals(product.getStatus())) {
                throw BusinessException.badRequest("商品非启用状态，不可执行采购退货: " + productLabel(product));
            }
        }
    }

    /** 商品展示名：优先通用名，缺失时回退编码/主键，保证错误文案含可识别商品信息。 */
    private static String productLabel(Product product) {
        if (product.getGenericName() != null && !product.getGenericName().isBlank()) {
            return product.getGenericName();
        }
        return product.getCode() == null ? String.valueOf(product.getId()) : product.getCode();
    }

    /** 回传实量解析：未提供明细则全部按单据量；提供则校验归属与非负。 */
    private Map<Long, BigDecimal> resolveActualQty(List<PurchaseReturnItem> items,
                                                   com.pharma.common.WmsCallbackRequest req) {
        Map<Long, BigDecimal> actual = new java.util.HashMap<>();
        if (req == null || req.items() == null || req.items().isEmpty()) {
            for (PurchaseReturnItem it : items) {
                actual.put(it.getId(), it.getQuantity());
            }
            return actual;
        }
        Map<Long, PurchaseReturnItem> byId = new java.util.HashMap<>();
        for (PurchaseReturnItem it : items) {
            byId.put(it.getId(), it);
        }
        for (com.pharma.common.WmsCallbackRequest.Item it : req.items()) {
            if (it == null || it.itemId() == null) {
                throw BusinessException.badRequest("回传明细 itemId 不能为空");
            }
            if (!byId.containsKey(it.itemId())) {
                throw BusinessException.badRequest("回传明细不属于该退货单: itemId=" + it.itemId());
            }
            BigDecimal qty = it.actualQty() == null ? BigDecimal.ZERO : it.actualQty();
            if (qty.compareTo(BigDecimal.ZERO) < 0) {
                throw BusinessException.badRequest("回传实际数量不能为负: itemId=" + it.itemId());
            }
            actual.put(it.itemId(), qty);
        }
        for (PurchaseReturnItem it : items) {
            actual.putIfAbsent(it.getId(), it.getQuantity());
        }
        return actual;
    }

    /**
     * 采购退货作废红冲（P2-11）：COMPLETED → 库存回补（IN 流水 + 成本台入）+ 反向追溯 + 原红字应付联动作废；
     * DRAFT / REJECTED → 仅置 VOID（未执行，无库存/财务影响）；SUBMITTED → 拒绝，需先驳回或完成审批。
     * 不物理删除。不校验制单人：由 purchase:return:void 权限码控制（退货专员可作废他人单据）。
     */
    @Transactional
    public PurchaseReturnVO voidReturn(Long id, String reason) {
        // 单据头悲观锁：防止作废与执行/回传并发交错
        PurchaseReturn header = returnRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("采购退货单不存在: " + id));
        if ("VOID".equals(header.getStatus())) {
            throw BusinessException.badRequest("采购退货单已作废: " + header.getReturnNo());
        }
        if ("SUBMITTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("待审批退货单不可直接作废，请先驳回或完成审批: "
                    + header.getReturnNo());
        }
        if ("PUSHED".equals(header.getStatus())) {
            throw BusinessException.badRequest("已下发 WMS 的退货单不可直接作废，请等待回传完成后再处理: "
                    + header.getReturnNo());
        }

        String before = header.getStatus();
        String opUser = ledger.currentUser();
        List<PurchaseReturnItem> items = returnItemRepository.findByReturnId(id);
        boolean executed = "COMPLETED".equals(before) || "ACTIVE".equals(before);
        if (executed) {
            for (PurchaseReturnItem item : items) {
                // 实量口径：只回补实际退货数量；实量为 0 的行执行时未扣库存，无需回补
                BigDecimal qty = executedQty(item);
                if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                String batchNo = item.getBatchNo();
                // 库存行悲观锁：回补前按 (商品, 批号, 仓库) 锁读，防止与并发出入库交错导致余额失真
                Stock stock = stockRepository
                        .findByProductIdAndBatchNoAndLocationIdForUpdate(
                                item.getProductId(), batchNo, item.getWarehouseId())
                        .orElseThrow(() -> BusinessException.badRequest(
                                "原批次库存不存在，无法回冲: 批号 " + batchNo));
                Stock updated = ledger.upsertStock(item.getProductId(), batchNo, stock.getProduceDate(),
                        stock.getExpireDate(), item.getWarehouseId(), qty, item.getUnitCost());
                BigDecimal after = updated.getQuantity();
                BigDecimal beforeQty = after.subtract(qty);
                ledger.writeStockTransaction(item.getProductId(), batchNo, OP_IN, REF_PURCHASE_RETURN,
                        header.getId(), item.getWarehouseId(), qty, beforeQty, after, opUser);
                ledger.applyCostIn(item.getProductId(), batchNo, qty, item.getUnitCost(),
                        ledger.currentBalance(item.getProductId()));
                ledger.writeLotTrace(item.getProductId(), batchNo, TRACE_PURCHASE_RETURN_VOID, header.getId(),
                        TRACE_STOCK, updated.getId(), qty, opUser);
            }

            // 冲回原红字应付
            arApService.voidByBiz(REF_PURCHASE_RETURN, header.getId());
        }

        header.setStatus("VOID");
        returnRepository.save(header);

        auditLogService.log(opUser, "VOID_PURCHASE_RETURN", REF_PURCHASE_RETURN, header.getId(),
                "作废采购退货单 " + header.getReturnNo() + (reason == null || reason.isBlank()
                        ? "" : "，原因：" + reason.trim()),
                AuditLogService.json("returnNo", header.getReturnNo(), "status", before,
                        "totalAmount", header.getTotalAmount(), "itemCount", items.size()),
                AuditLogService.json("returnNo", header.getReturnNo(), "status", "VOID",
                        "totalAmount", header.getTotalAmount(), "itemCount", items.size(), "reason", reason));

        String receiveNo = header.getReceiveId() == null ? null
                : receiveRepository.findById(header.getReceiveId()).map(PurchaseReceive::getReceiveNo).orElse(null);
        return toVO(header, items, receiveNo, supplierName(header.getSupplierId()));
    }

    /**
     * 列表：不按 createdBy 隔离（退货专员可见全部采购退货单），可见性由权限码控制。
     */
    public PageResult<PurchaseReturnVO> list(String keyword, Long supplierId, int page, int size) {
        return list(keyword, supplierId, null, page, size);
    }

    /** 列表（批次十四：支持按状态过滤）。 */
    public PageResult<PurchaseReturnVO> list(String keyword, Long supplierId, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<PurchaseReturn> result = returnRepository.search(
                keyword == null || keyword.isBlank() ? null : keyword.trim(), supplierId,
                null, status == null || status.isBlank() ? null : status.trim(), pageable);

        List<Long> ids = result.getContent().stream().map(PurchaseReturn::getId).toList();
        Map<Long, List<PurchaseReturnItem>> itemMap = returnItemRepository.findByReturnIdIn(ids).stream()
                .collect(Collectors.groupingBy(PurchaseReturnItem::getReturnId));
        Map<Long, String> productNames = productNames(itemMap.values().stream().flatMap(List::stream)
                .map(PurchaseReturnItem::getProductId).toList());
        Map<Long, String> supplierNames = supplierNames(result.getContent().stream()
                .map(PurchaseReturn::getSupplierId).toList());
        Map<Long, String> receiveNos = receiveRepository.findAllById(result.getContent().stream()
                        .map(PurchaseReturn::getReceiveId).filter(java.util.Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(PurchaseReceive::getId, PurchaseReceive::getReceiveNo));

        List<PurchaseReturnVO> records = result.getContent().stream()
                .map(h -> toVOWithNames(h, itemMap.getOrDefault(h.getId(), List.of()),
                        h.getReceiveId() == null ? null : receiveNos.get(h.getReceiveId()),
                        supplierNames.get(h.getSupplierId()), productNames))
                .toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    /** 采购退货详情：含明细、原入库单号与供应商名；不存在 404。 */
    public PurchaseReturnVO get(Long id) {
        PurchaseReturn header = returnRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("采购退货单不存在: " + id));
        String receiveNo = header.getReceiveId() == null ? null
                : receiveRepository.findById(header.getReceiveId())
                .map(PurchaseReceive::getReceiveNo).orElse(null);
        return toVO(header, returnItemRepository.findByReturnId(id), receiveNo,
                supplierName(header.getSupplierId()));
    }

    /**
     * 导出用全量查询（上限 {@link ExportWriter#MAX_ROWS}）：复用列表的查询条件，不分页。
     */
    public ExportPayload<PurchaseReturnExportRow> exportRows(String keyword, Long supplierId) {
        return exportRows(keyword, supplierId, null);
    }

    public ExportPayload<PurchaseReturnExportRow> exportRows(String keyword, Long supplierId, String status) {
        Pageable pageable = PageRequest.of(0, ExportWriter.MAX_ROWS, Sort.by(Sort.Direction.DESC, "id"));
        Page<PurchaseReturn> result = returnRepository.search(
                keyword == null || keyword.isBlank() ? null : keyword.trim(), supplierId, null,
                status == null || status.isBlank() ? null : status.trim(), pageable);
        Map<Long, String> supplierNames = supplierNames(result.getContent().stream()
                .map(PurchaseReturn::getSupplierId).toList());
        List<PurchaseReturnExportRow> rows = result.getContent().stream()
                .map(h -> new PurchaseReturnExportRow(h.getReturnNo(), billDate(h.getReturnDate()),
                        supplierNames.get(h.getSupplierId()), h.getTotalAmount(), h.getStatus(), h.getCreatedBy()))
                .toList();
        return new ExportPayload<>(rows, result.getTotalElements(), ExportWriter.MAX_ROWS);
    }

    private static String billDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    /** 建单/编辑共用校验：供应商、业务日期、明细品种/批号/数量、温控兼容、批次库存充足（不扣减）。 */
    private Prepared prepare(PurchaseReturnCreateRequest req) {
        if (req == null) {
            throw BusinessException.badRequest("采购退货请求不能为空");
        }
        dateValidator.validateBizDate(req.returnDate(), "退货日期");
        String receiveNo = null;
        Long supplierId = req.supplierId();
        if (req.receiveId() != null) {
            PurchaseReceive receive = receiveRepository.findById(req.receiveId())
                    .orElseThrow(() -> BusinessException.notFound("采购入库单不存在: " + req.receiveId()));
            PurchaseOrder order = orderRepository.findById(receive.getOrderId())
                    .orElseThrow(() -> BusinessException.notFound("采购订单不存在: " + receive.getOrderId()));
            supplierId = order.getSupplierId();
            receiveNo = receive.getReceiveNo();
        }
        if (supplierId == null) {
            throw BusinessException.badRequest("退货供应商不能为空");
        }
        final Long checkedSupplierId = supplierId;
        supplierRepository.findById(checkedSupplierId)
                .orElseThrow(() -> BusinessException.badRequest("供应商不存在: " + checkedSupplierId));

        List<PurchaseReturnItemRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("退货明细不能为空");
        }

        List<PurchaseReturnItem> built = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseReturnItemRequest it : items) {
            if (it.productId() == null) {
                throw BusinessException.badRequest("商品不能为空");
            }
            if (it.batchNo() == null || it.batchNo().isBlank()) {
                throw BusinessException.badRequest("退货批号不能为空");
            }
            if (it.quantity() == null || it.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("退货数量必须大于0");
            }
            if (it.warehouseId() == null) {
                throw BusinessException.badRequest("退货出库仓库不能为空");
            }
            Long locationId = ledger.requireLocationId(it.warehouseId());
            String batchNo = it.batchNo().trim();
            // 温控兼容（P2-9）：退货出库仓必须与商品存储条件匹配（如冷藏品不可记在常温仓）
            Product product = productRepository.findById(it.productId()).orElse(null);
            Warehouse warehouse = warehouseRepository.findById(locationId).orElse(null);
            if (product != null && warehouse != null) {
                TemperatureCompatibility.requireCompatible(product.getCode(), product.getStorageCond(),
                        warehouse.getTempType(), warehouse.getName(), "采购退货出库");
            }
            Stock stock = stockRepository.findByProductIdAndBatchNoAndLocationId(it.productId(), batchNo, locationId)
                    .orElseThrow(() -> BusinessException.badRequest("退货批次不存在: 商品 "
                            + it.productId() + " 批号 " + batchNo));
            if (stock.getQuantity().compareTo(it.quantity()) < 0) {
                throw BusinessException.badRequest("库存不足，无法退货: 批次 " + batchNo + " 现存 "
                        + stock.getQuantity().toPlainString() + " < 退货 " + it.quantity().toPlainString());
            }

            PurchaseReturnItem entity = new PurchaseReturnItem();
            entity.setProductId(it.productId());
            entity.setBatchNo(batchNo);
            entity.setQuantity(it.quantity());
            entity.setUnitCost(stock.getUnitCost());
            entity.setWarehouseId(locationId);
            built.add(entity);
            total = total.add(it.quantity().multiply(stock.getUnitCost()));
        }
        return new Prepared(supplierId, receiveNo, built, total.setScale(2, RoundingMode.HALF_UP));
    }

    private List<PurchaseReturnItem> saveItems(Long returnId, List<PurchaseReturnItem> items) {
        List<PurchaseReturnItem> saved = new ArrayList<>();
        for (PurchaseReturnItem item : items) {
            item.setReturnId(returnId);
            saved.add(returnItemRepository.save(item));
        }
        return saved;
    }

    private PurchaseReturnVO toVO(PurchaseReturn header, List<PurchaseReturnItem> items,
                                  String receiveNo, String supplierName) {
        Map<Long, String> names = productNames(items.stream().map(PurchaseReturnItem::getProductId).toList());
        return toVOWithNames(header, items, receiveNo, supplierName, names);
    }

    private PurchaseReturnVO toVOWithNames(PurchaseReturn header, List<PurchaseReturnItem> items, String receiveNo,
                                           String supplierName, Map<Long, String> productNames) {
        List<PurchaseReturnItemVO> vos = items.stream()
                .map(i -> new PurchaseReturnItemVO(i.getId(), i.getProductId(), productNames.get(i.getProductId()),
                        i.getBatchNo(), i.getQuantity(), i.getUnitCost(), i.getWarehouseId(),
                        i.getActualQty(), i.getQtyDiff()))
                .toList();
        return new PurchaseReturnVO(header.getId(), header.getReturnNo(), header.getReceiveId(), receiveNo,
                header.getSupplierId(), supplierName, header.getReturnDate(), header.getStatus(),
                header.getTotalAmount(), header.getReason(), header.getCreatedBy(), header.getCreatedAt(),
                header.getWmsNo(), header.getWmsPushedAt(), header.getWmsCallbackAt(), vos);
    }

    private String supplierName(Long supplierId) {
        return supplierRepository.findById(supplierId).map(Supplier::getName).orElse(null);
    }

    private Map<Long, String> supplierNames(List<Long> ids) {
        Set<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return supplierRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName));
    }

    private Map<Long, String> productNames(List<Long> ids) {
        Set<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return productRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(Product::getId,
                        p -> p.getGenericName() == null ? "" : p.getGenericName()));
    }
}
