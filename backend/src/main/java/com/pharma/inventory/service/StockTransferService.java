package com.pharma.inventory.service;

import com.pharma.approval.dto.ApprovalTarget;
import com.pharma.approval.entity.ApprovalRecord;
import com.pharma.approval.repository.ApprovalRecordRepository;
import com.pharma.approval.service.ApprovalLevelResolver;
import com.pharma.base.entity.Product;
import com.pharma.base.entity.Warehouse;
import com.pharma.base.repository.ProductRepository;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.DataScope;
import com.pharma.common.DateValidator;
import com.pharma.common.PageResult;
import com.pharma.common.TemperatureCompatibility;
import com.pharma.common.export.ExportPayload;
import com.pharma.common.export.ExportWriter;
import com.pharma.inventory.dto.StockTransferCreateRequest;
import com.pharma.inventory.dto.StockTransferExportRow;
import com.pharma.inventory.dto.StockTransferItemRequest;
import com.pharma.inventory.dto.StockTransferItemVO;
import com.pharma.inventory.dto.StockTransferVO;
import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.entity.StockTransfer;
import com.pharma.inventory.entity.StockTransferItem;
import com.pharma.inventory.repository.StockRepository;
import com.pharma.inventory.repository.StockTransferItemRepository;
import com.pharma.inventory.repository.StockTransferRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.pharma.inventory.service.InventoryLedgerService.OP_IN;
import static com.pharma.inventory.service.InventoryLedgerService.REF_TRANSFER;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_STOCK;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_TRANSFER_ITEM;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_TRANSFER_VOID;

/**
 * 仓库调拨（P1-12）：DRAFT（建单只记明细）→ SUBMITTED → APPROVED → 执行 → COMPLETED。
 * 执行内容：同一事务内 from 仓指定批次扣减 + to 仓 upsert（同批号/效期/成本），
 * 写两条 ref_type=TRANSFER 流水（OUT/IN），库存不足整体拒绝。审批通过前不调拨（批次十四决策 2）。
 */
@Service
public class StockTransferService {

    public static final String BIZ_TYPE = "STOCK_TRANSFER";

    private final StockTransferRepository transferRepository;
    private final StockTransferItemRepository transferItemRepository;
    private final StockRepository stockRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final InventoryLedgerService ledger;
    private final AuditLogService auditLogService;
    private final DateValidator dateValidator;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final ApprovalLevelResolver approvalLevelResolver;

    public StockTransferService(StockTransferRepository transferRepository,
                                StockTransferItemRepository transferItemRepository,
                                StockRepository stockRepository,
                                WarehouseRepository warehouseRepository,
                                ProductRepository productRepository,
                                InventoryLedgerService ledger,
                                AuditLogService auditLogService,
                                DateValidator dateValidator,
                                ApprovalRecordRepository approvalRecordRepository,
                                ApprovalLevelResolver approvalLevelResolver) {
        this.transferRepository = transferRepository;
        this.transferItemRepository = transferItemRepository;
        this.stockRepository = stockRepository;
        this.warehouseRepository = warehouseRepository;
        this.productRepository = productRepository;
        this.ledger = ledger;
        this.auditLogService = auditLogService;
        this.dateValidator = dateValidator;
        this.approvalRecordRepository = approvalRecordRepository;
        this.approvalLevelResolver = approvalLevelResolver;
    }

    /** 已校验的建单参数（create / update 共用）。 */
    private record Prepared(Long fromId, Long toId, List<StockTransferItem> items, BigDecimal total) {
    }

    /**
     * 建单：只写单据头 + 明细（含按调出仓批次成本预估的调拨金额），状态 DRAFT。
     * 校验批次存在 / 调出仓库存充足 / 温控兼容，但不在此时移库。
     */
    @Transactional
    public StockTransferVO create(StockTransferCreateRequest req) {
        Prepared prepared = prepare(req);

        StockTransfer header = new StockTransfer();
        header.setTransferNo(ledger.genNo("TR"));
        header.setFromWarehouseId(prepared.fromId());
        header.setToWarehouseId(prepared.toId());
        header.setTransferDate(req.transferDate() == null ? LocalDate.now() : req.transferDate());
        header.setStatus("DRAFT");
        header.setTotalAmount(prepared.total());
        header.setRemark(req.remark());
        header.setCreatedBy(ledger.currentUser());
        header = transferRepository.save(header);

        List<StockTransferItem> saved = saveItems(header.getId(), prepared.items());

        auditLogService.log(header.getCreatedBy(), "CREATE_STOCK_TRANSFER", BIZ_TYPE, header.getId(),
                "创建仓库调拨单 " + header.getTransferNo() + "（待提交），" + saved.size() + " 条明细");
        return toVO(header, saved, warehouseName(prepared.fromId()), warehouseName(prepared.toId()),
                productNames(saved.stream().map(StockTransferItem::getProductId).toList()));
    }

    /** 编辑：仅草稿或已驳回可改，整单重建明细；已驳回改完仍为 REJECTED，需重新提交。 */
    @Transactional
    public StockTransferVO update(Long id, StockTransferCreateRequest req) {
        StockTransfer header = transferRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("调拨单不存在: " + id));
        if (!"DRAFT".equals(header.getStatus()) && !"REJECTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回调拨单可编辑，当前状态: " + header.getStatus());
        }
        Prepared prepared = prepare(req);
        header.setFromWarehouseId(prepared.fromId());
        header.setToWarehouseId(prepared.toId());
        if (req.transferDate() != null) {
            header.setTransferDate(req.transferDate());
        }
        header.setTotalAmount(prepared.total());
        header.setRemark(req.remark());
        transferRepository.save(header);

        transferItemRepository.deleteAll(transferItemRepository.findByTransferId(id));
        List<StockTransferItem> saved = saveItems(id, prepared.items());

        auditLogService.log(ledger.currentUser(), "UPDATE_STOCK_TRANSFER", BIZ_TYPE, id,
                "编辑仓库调拨单 " + header.getTransferNo() + "，" + saved.size() + " 条明细");
        return toVO(header, saved, warehouseName(prepared.fromId()), warehouseName(prepared.toId()),
                productNames(saved.stream().map(StockTransferItem::getProductId).toList()));
    }

    /** 提审：DRAFT / REJECTED -> SUBMITTED，写 PENDING approval_record（金额分级节点）。 */
    @Transactional
    public StockTransferVO submit(Long id) {
        StockTransfer header = transferRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("调拨单不存在: " + id));
        if (!"DRAFT".equals(header.getStatus()) && !"REJECTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回调拨单可提交，当前状态: " + header.getStatus());
        }
        if (transferItemRepository.findByTransferId(id).isEmpty()) {
            throw BusinessException.badRequest("调拨明细不能为空");
        }
        String before = header.getStatus();
        header.setStatus("SUBMITTED");
        transferRepository.save(header);

        ApprovalRecord record = new ApprovalRecord();
        record.setBizType(BIZ_TYPE);
        record.setBizId(header.getId());
        record.setNode(approvalLevelResolver.node(BIZ_TYPE, header.getTotalAmount()));
        record.setStatus("PENDING");
        approvalRecordRepository.save(record);

        auditLogService.log(ledger.currentUser(), "SUBMIT_STOCK_TRANSFER", BIZ_TYPE, header.getId(),
                "提交仓库调拨单 " + header.getTransferNo() + "，金额 " + header.getTotalAmount().toPlainString(),
                AuditLogService.json("transferNo", header.getTransferNo(), "status", before),
                AuditLogService.json("transferNo", header.getTransferNo(), "status", "SUBMITTED",
                        "node", record.getNode()));
        return get(id);
    }

    /** 审批驳回：SUBMITTED -> REJECTED（可编辑后重新提交）。 */
    @Transactional
    public void reject(Long id) {
        StockTransfer header = transferRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("调拨单不存在: " + id));
        if (!"SUBMITTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅已提交状态可驳回，当前状态: " + header.getStatus());
        }
        header.setStatus("REJECTED");
        transferRepository.save(header);
    }

    /** 审批上下文（状态守卫 / 回避校验 / 金额分级）。 */
    public ApprovalTarget approvalTarget(Long id) {
        StockTransfer header = transferRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("调拨单不存在: " + id));
        return new ApprovalTarget(header.getTransferNo(), header.getStatus(), header.getCreatedBy(),
                header.getTotalAmount());
    }

    /**
     * 审批通过：SUBMITTED -> APPROVED -> PUSHED（下发 WMS），仅状态推进，不移动库存。
     */
    @Transactional
    public void approveDocument(Long id) {
        StockTransfer header = transferRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("调拨单不存在: " + id));
        if (!"SUBMITTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅已提交状态可审批，当前状态: " + header.getStatus());
        }
        header.setStatus("APPROVED");
        transferRepository.save(header);
        header.setStatus("PUSHED");
        header.setWmsPushedAt(java.time.LocalDateTime.now());
        transferRepository.save(header);

        auditLogService.log(ledger.currentUser(), "STOCK_TRANSFER_PUSHED", BIZ_TYPE, header.getId(),
                "仓库调拨单 " + header.getTransferNo() + " 审批通过并下发 WMS，" + header.getTotalAmount().toPlainString());
    }

    /**
     * WMS 回传：仅 PUSHED 可回传；按实调量执行移库并置 COMPLETED；重复回传 400。
     */
    @Transactional
    public StockTransferVO wmsCallback(Long id, com.pharma.common.WmsCallbackRequest req) {
        StockTransfer header = transferRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("调拨单不存在: " + id));
        if (!"PUSHED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅已下发 WMS 的单据可回传实量，当前状态: " + header.getStatus());
        }
        Map<Long, BigDecimal> actual = resolveActualQty(transferItemRepository.findByTransferId(id), req);
        header.setWmsNo(req == null ? null : req.wmsNo());
        header.setWmsCallbackAt(req == null || req.callbackTime() == null
                ? java.time.LocalDateTime.now() : req.callbackTime());
        transferRepository.save(header);

        executeActual(id, actual);
        return get(id);
    }

    /**
     * 按 WMS 实调量执行调拨：actualQty 为 0 的行跳过；每行写 actual_qty / qty_diff；
     * 执行完置 COMPLETED。
     */
    @Transactional
    public StockTransferVO executeActual(Long id, Map<Long, BigDecimal> actualQtyByItemId) {
        StockTransfer header = transferRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("调拨单不存在: " + id));
        if (!"PUSHED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅已下发 WMS 的单据可执行调拨，当前状态: " + header.getStatus());
        }
        List<StockTransferItem> items = transferItemRepository.findByTransferId(id);
        if (items.isEmpty()) {
            throw BusinessException.badRequest("调拨明细不能为空");
        }

        Long fromId = header.getFromWarehouseId();
        Long toId = header.getToWarehouseId();
        String opUser = ledger.currentUser();
        BigDecimal total = BigDecimal.ZERO;
        for (StockTransferItem item : items) {
            BigDecimal actual = actualQtyByItemId == null || actualQtyByItemId.get(item.getId()) == null
                    ? item.getQuantity() : actualQtyByItemId.get(item.getId());
            item.setActualQty(actual);
            item.setQtyDiff(actual.subtract(item.getQuantity()));
            if (actual.compareTo(BigDecimal.ZERO) <= 0) {
                transferItemRepository.save(item);
                continue;
            }
            String batchNo = item.getBatchNo();
            Stock fromStock = stockRepository
                    .findByProductIdAndBatchNoAndLocationId(item.getProductId(), batchNo, fromId)
                    .orElseThrow(() -> BusinessException.badRequest("调出批次不存在: 商品 "
                            + item.getProductId() + " 批号 " + batchNo));

            BigDecimal unitCost = ledger.transferOutbound(item.getProductId(), batchNo, fromId,
                    actual, opUser, header.getId());

            Stock toStock = ledger.upsertStock(item.getProductId(), batchNo, fromStock.getProduceDate(),
                    fromStock.getExpireDate(), toId, actual, unitCost);
            BigDecimal after = toStock.getQuantity();
            BigDecimal before = after.subtract(actual);
            ledger.writeStockTransaction(item.getProductId(), batchNo, OP_IN, REF_TRANSFER, header.getId(),
                    toId, actual, before, after, opUser);
            ledger.applyCostIn(item.getProductId(), batchNo, actual, unitCost,
                    ledger.currentBalance(item.getProductId()));

            ledger.writeLotTrace(item.getProductId(), batchNo, TRACE_STOCK, fromStock.getId(),
                    TRACE_TRANSFER_ITEM, item.getId(), actual, opUser);
            ledger.writeLotTrace(item.getProductId(), batchNo, TRACE_TRANSFER_ITEM, item.getId(),
                    TRACE_STOCK, toStock.getId(), actual, opUser);
            transferItemRepository.save(item);

            total = total.add(actual.multiply(unitCost));
        }

        header.setTotalAmount(total.setScale(2, java.math.RoundingMode.HALF_UP));
        header.setStatus("COMPLETED");
        transferRepository.save(header);

        auditLogService.log(opUser, "STOCK_TRANSFER_COMPLETED", BIZ_TYPE, header.getId(),
                "仓库调拨单 " + header.getTransferNo() + " WMS 回传完成，金额 "
                        + header.getTotalAmount().toPlainString() + "，" + items.size() + " 条明细");

        return toVO(header, items, warehouseName(fromId), warehouseName(toId),
                productNames(items.stream().map(StockTransferItem::getProductId).toList()));
    }

    /** 回传实量解析：未提供明细则全部按单据量；提供则校验归属与非负。 */
    private Map<Long, BigDecimal> resolveActualQty(List<StockTransferItem> items,
                                                   com.pharma.common.WmsCallbackRequest req) {
        Map<Long, BigDecimal> actual = new java.util.HashMap<>();
        if (req == null || req.items() == null || req.items().isEmpty()) {
            for (StockTransferItem it : items) {
                actual.put(it.getId(), it.getQuantity());
            }
            return actual;
        }
        Map<Long, StockTransferItem> byId = new java.util.HashMap<>();
        for (StockTransferItem it : items) {
            byId.put(it.getId(), it);
        }
        for (com.pharma.common.WmsCallbackRequest.Item it : req.items()) {
            if (it == null || it.itemId() == null) {
                throw BusinessException.badRequest("回传明细 itemId 不能为空");
            }
            if (!byId.containsKey(it.itemId())) {
                throw BusinessException.badRequest("回传明细不属于该调拨单: itemId=" + it.itemId());
            }
            BigDecimal qty = it.actualQty() == null ? BigDecimal.ZERO : it.actualQty();
            if (qty.compareTo(BigDecimal.ZERO) < 0) {
                throw BusinessException.badRequest("回传实际数量不能为负: itemId=" + it.itemId());
            }
            actual.put(it.itemId(), qty);
        }
        for (StockTransferItem it : items) {
            actual.putIfAbsent(it.getId(), it.getQuantity());
        }
        return actual;
    }

    /**
     * 调拨作废红冲（P2-11）：COMPLETED → 反向流水（调入仓 OUT、调出仓 IN）+ 反向成本台账 + 作废追溯；
     * DRAFT / REJECTED → 仅置 VOID（未执行，无库存影响）；SUBMITTED → 拒绝，需先驳回或完成审批。
     * 不物理删除；调入仓库存不足时整体拒绝。
     */
    @Transactional
    public StockTransferVO voidTransfer(Long id, String reason) {
        StockTransfer header = transferRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("调拨单不存在: " + id));
        DataScope.requireOwnership(header.getCreatedBy());
        if ("VOID".equals(header.getStatus())) {
            throw BusinessException.badRequest("调拨单已作废: " + header.getTransferNo());
        }
        if ("SUBMITTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("待审批调拨单不可直接作废，请先驳回或完成审批: "
                    + header.getTransferNo());
        }
        if ("PUSHED".equals(header.getStatus())) {
            throw BusinessException.badRequest("已下发 WMS 的调拨单不可直接作废，请等待回传完成后再处理: "
                    + header.getTransferNo());
        }
        String before = header.getStatus();
        String opUser = ledger.currentUser();
        List<StockTransferItem> items = transferItemRepository.findByTransferId(id);
        boolean executed = "COMPLETED".equals(before) || "ACTIVE".equals(before);
        if (executed) {
            for (StockTransferItem item : items) {
                String batchNo = item.getBatchNo();
                BigDecimal qty = item.getQuantity();
                Stock toStock = stockRepository
                        .findByProductIdAndBatchNoAndLocationId(item.getProductId(), batchNo, header.getToWarehouseId())
                        .orElseThrow(() -> BusinessException.badRequest("调入仓批次不存在，无法作废: 批号 " + batchNo));

                // 调入仓扣回（OUT 流水 + 成本台出）
                BigDecimal unitCost = ledger.outboundBatch(item.getProductId(), batchNo, header.getToWarehouseId(),
                        qty, opUser, REF_TRANSFER, header.getId());

                // 调出仓补回（IN 流水 + 成本台入），保留原批次生产/效期
                Stock fromStock = ledger.upsertStock(item.getProductId(), batchNo, toStock.getProduceDate(),
                        toStock.getExpireDate(), header.getFromWarehouseId(), qty, unitCost);
                BigDecimal after = fromStock.getQuantity();
                BigDecimal beforeQty = after.subtract(qty);
                ledger.writeStockTransaction(item.getProductId(), batchNo, OP_IN, REF_TRANSFER, header.getId(),
                        header.getFromWarehouseId(), qty, beforeQty, after, opUser);
                ledger.applyCostIn(item.getProductId(), batchNo, qty, unitCost,
                        ledger.currentBalance(item.getProductId()));

                ledger.writeLotTrace(item.getProductId(), batchNo, TRACE_TRANSFER_VOID, header.getId(),
                        TRACE_STOCK, fromStock.getId(), qty, opUser);
                ledger.writeLotTrace(item.getProductId(), batchNo, TRACE_STOCK, toStock.getId(),
                        TRACE_TRANSFER_VOID, header.getId(), qty, opUser);
            }
        }

        header.setStatus("VOID");
        transferRepository.save(header);

        auditLogService.log(opUser, "VOID_TRANSFER", REF_TRANSFER, header.getId(),
                "作废仓库调拨单 " + header.getTransferNo() + (reason == null || reason.isBlank()
                        ? "" : "，原因：" + reason.trim()),
                AuditLogService.json("transferNo", header.getTransferNo(), "status", before,
                        "itemCount", items.size(),
                        "totalQuantity", items.stream().map(StockTransferItem::getQuantity)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)),
                AuditLogService.json("transferNo", header.getTransferNo(), "status", "VOID",
                        "itemCount", items.size(),
                        "totalQuantity", items.stream().map(StockTransferItem::getQuantity)
                                .reduce(BigDecimal.ZERO, BigDecimal::add), "reason", reason));

        return toVO(header, items, warehouseName(header.getFromWarehouseId()),
                warehouseName(header.getToWarehouseId()),
                productNames(items.stream().map(StockTransferItem::getProductId).toList()));
    }

    /** 调拨单详情：含明细与调出/调入仓名称；不存在 404。 */
    public StockTransferVO get(Long id) {
        StockTransfer header = transferRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("调拨单不存在: " + id));
        List<StockTransferItem> items = transferItemRepository.findByTransferId(id);
        return toVO(header, items, warehouseName(header.getFromWarehouseId()),
                warehouseName(header.getToWarehouseId()),
                productNames(items.stream().map(StockTransferItem::getProductId).toList()));
    }

    /**
     * 导出用全量查询（上限 {@link ExportWriter#MAX_ROWS}）：复用列表的查询条件，不分页。
     */
    public ExportPayload<StockTransferExportRow> exportRows(String keyword) {
        return exportRows(keyword, null);
    }

    public ExportPayload<StockTransferExportRow> exportRows(String keyword, String status) {
        Pageable pageable = PageRequest.of(0, ExportWriter.MAX_ROWS, Sort.by(Sort.Direction.DESC, "id"));
        Page<StockTransfer> result = transferRepository.search(
                keyword == null || keyword.isBlank() ? null : keyword.trim(), null,
                status == null || status.isBlank() ? null : status.trim(), pageable);
        List<StockTransfer> content = result.getContent();
        Set<Long> warehouseIds = content.stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getFromWarehouseId(), t.getToWarehouseId()))
                .collect(Collectors.toSet());
        Map<Long, String> warehouseNames = warehouseIds.isEmpty() ? Map.of()
                : warehouseRepository.findAllById(warehouseIds).stream()
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName));
        List<StockTransferExportRow> rows = content.stream()
                .map(t -> new StockTransferExportRow(t.getTransferNo(),
                        t.getTransferDate() == null ? "" : t.getTransferDate().toString(),
                        warehouseNames.get(t.getFromWarehouseId()),
                        warehouseNames.get(t.getToWarehouseId()),
                        t.getStatus(), t.getCreatedBy(), t.getRemark()))
                .toList();
        return new ExportPayload<>(rows, result.getTotalElements(), ExportWriter.MAX_ROWS);
    }

    /**
     * 列表：不按 createdBy 隔离（仓管/调拨角色可见全部调拨单），可见性由权限码控制。
     */
    public PageResult<StockTransferVO> list(String keyword, int page, int size) {
        return list(keyword, null, page, size);
    }

    /** 列表（批次十四：支持按状态过滤）。 */
    public PageResult<StockTransferVO> list(String keyword, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<StockTransfer> result = transferRepository.search(
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                null, status == null || status.isBlank() ? null : status.trim(), pageable);

        List<Long> ids = result.getContent().stream().map(StockTransfer::getId).toList();
        Map<Long, List<StockTransferItem>> itemMap = transferItemRepository.findByTransferIdIn(ids).stream()
                .collect(Collectors.groupingBy(StockTransferItem::getTransferId));
        Map<Long, String> productNames = productNames(itemMap.values().stream().flatMap(List::stream)
                .map(StockTransferItem::getProductId).toList());
        Set<Long> warehouseIds = result.getContent().stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getFromWarehouseId(), t.getToWarehouseId()))
                .collect(Collectors.toSet());
        Map<Long, String> warehouseNames = warehouseIds.isEmpty() ? Map.of()
                : warehouseRepository.findAllById(warehouseIds).stream()
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName));

        List<StockTransferVO> records = result.getContent().stream()
                .map(t -> toVO(t, itemMap.getOrDefault(t.getId(), List.of()),
                        warehouseNames.get(t.getFromWarehouseId()),
                        warehouseNames.get(t.getToWarehouseId()), productNames))
                .toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    /** 建单/编辑共用校验：仓库、业务日期、明细品种/批号/数量、调出仓库存充足、温控兼容（不扣减）。 */
    private Prepared prepare(StockTransferCreateRequest req) {
        if (req == null || req.fromWarehouseId() == null || req.toWarehouseId() == null) {
            throw BusinessException.badRequest("调出/调入仓库不能为空");
        }
        dateValidator.validateBizDate(req.transferDate(), "调拨日期");
        if (req.fromWarehouseId().equals(req.toWarehouseId())) {
            throw BusinessException.badRequest("调出仓库与调入仓库不能相同");
        }
        Long fromId = ledger.requireLocationId(req.fromWarehouseId());
        Long toId = ledger.requireLocationId(req.toWarehouseId());
        Warehouse toWarehouse = warehouseRepository.findById(toId)
                .orElseThrow(() -> BusinessException.badRequest("调入仓库不存在: " + req.toWarehouseId()));
        List<StockTransferItemRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("调拨明细不能为空");
        }

        List<StockTransferItem> built = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (StockTransferItemRequest it : items) {
            if (it.productId() == null) {
                throw BusinessException.badRequest("商品不能为空");
            }
            if (it.batchNo() == null || it.batchNo().isBlank()) {
                throw BusinessException.badRequest("调拨批号不能为空");
            }
            if (it.quantity() == null || it.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("调拨数量必须大于0");
            }
            String batchNo = it.batchNo().trim();
            Product product = productRepository.findById(it.productId()).orElse(null);
            if (product != null) {
                TemperatureCompatibility.requireCompatible(product.getCode(), product.getStorageCond(),
                        toWarehouse.getTempType(), toWarehouse.getName(), "调拨目标仓");
            }
            Stock fromStock = stockRepository
                    .findByProductIdAndBatchNoAndLocationId(it.productId(), batchNo, fromId)
                    .orElseThrow(() -> BusinessException.badRequest("调出批次不存在: 商品 "
                            + it.productId() + " 批号 " + batchNo));
            if (fromStock.getQuantity().compareTo(it.quantity()) < 0) {
                throw BusinessException.badRequest("调出仓库存不足，无法调拨: 批次 " + batchNo + " 现存 "
                        + fromStock.getQuantity().toPlainString() + " < 调拨 " + it.quantity().toPlainString());
            }

            StockTransferItem entity = new StockTransferItem();
            entity.setProductId(it.productId());
            entity.setBatchNo(batchNo);
            entity.setQuantity(it.quantity());
            built.add(entity);
            total = total.add(it.quantity().multiply(fromStock.getUnitCost()));
        }
        return new Prepared(fromId, toId, built, total.setScale(2, RoundingMode.HALF_UP));
    }

    private List<StockTransferItem> saveItems(Long transferId, List<StockTransferItem> items) {
        List<StockTransferItem> saved = new ArrayList<>();
        for (StockTransferItem item : items) {
            item.setTransferId(transferId);
            saved.add(transferItemRepository.save(item));
        }
        return saved;
    }

    private StockTransferVO toVO(StockTransfer header, List<StockTransferItem> items,
                                 String fromName, String toName, Map<Long, String> productNames) {
        List<StockTransferItemVO> vos = items.stream()
                .map(i -> new StockTransferItemVO(i.getId(), i.getProductId(), productNames.get(i.getProductId()),
                        i.getBatchNo(), i.getQuantity(), i.getActualQty(), i.getQtyDiff()))
                .toList();
        return new StockTransferVO(header.getId(), header.getTransferNo(), header.getFromWarehouseId(), fromName,
                header.getToWarehouseId(), toName, header.getTransferDate(), header.getStatus(),
                header.getRemark(), header.getCreatedBy(), header.getCreatedAt(),
                header.getWmsNo(), header.getWmsPushedAt(), header.getWmsCallbackAt(), vos);
    }

    private String warehouseName(Long id) {
        return warehouseRepository.findById(id).map(Warehouse::getName).orElse(null);
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
