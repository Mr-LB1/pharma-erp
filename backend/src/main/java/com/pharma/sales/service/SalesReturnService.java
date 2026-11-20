package com.pharma.sales.service;

import com.pharma.approval.entity.ApprovalRecord;
import com.pharma.approval.repository.ApprovalRecordRepository;
import com.pharma.approval.service.ApprovalLevelResolver;
import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Product;
import com.pharma.base.entity.Warehouse;
import com.pharma.base.repository.CustomerRepository;
import com.pharma.base.repository.ProductRepository;
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
import com.pharma.quality.service.UnqualifiedService;
import com.pharma.sales.dto.*;
import com.pharma.sales.entity.SalesDelivery;
import com.pharma.sales.entity.SalesDeliveryItem;
import com.pharma.sales.entity.SalesOrder;
import com.pharma.sales.entity.SalesOrderItem;
import com.pharma.sales.entity.SalesReturn;
import com.pharma.sales.entity.SalesReturnItem;
import com.pharma.sales.repository.*;
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
import static com.pharma.inventory.service.InventoryLedgerService.REF_SALES_RETURN;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_SALES_RETURN_ITEM;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_SALES_RETURN_VOID;
import static com.pharma.inventory.service.InventoryLedgerService.TRACE_STOCK;

/**
 * 销售退货（P1-11）：DRAFT → SUBMITTED → APPROVED（ApprovalService）→ RECEIVED（验收分流）。
 * 验收合格：写 IN 流水 + 按原 unit_cost 回冲成本；不合格：UnqualifiedService 登记隔离。
 * 验收完成后生成负数应收（红字）用于对账抵扣。
 */
@Service
public class SalesReturnService {

    public static final String BIZ_TYPE = "SALES_RETURN";

    private final SalesReturnRepository returnRepository;
    private final SalesReturnItemRepository returnItemRepository;
    private final SalesDeliveryRepository deliveryRepository;
    private final SalesDeliveryItemRepository deliveryItemRepository;
    private final SalesOrderRepository orderRepository;
    private final SalesOrderItemRepository orderItemRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockRepository stockRepository;
    private final InventoryLedgerService ledger;
    private final UnqualifiedService unqualifiedService;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final AuditLogService auditLogService;
    private final ArApService arApService;
    private final DateValidator dateValidator;
    private final ApprovalLevelResolver approvalLevelResolver;

    public SalesReturnService(SalesReturnRepository returnRepository,
                              SalesReturnItemRepository returnItemRepository,
                              SalesDeliveryRepository deliveryRepository,
                              SalesDeliveryItemRepository deliveryItemRepository,
                              SalesOrderRepository orderRepository,
                              SalesOrderItemRepository orderItemRepository,
                              CustomerRepository customerRepository,
                              ProductRepository productRepository,
                              WarehouseRepository warehouseRepository,
                              StockRepository stockRepository,
                              InventoryLedgerService ledger,
                              UnqualifiedService unqualifiedService,
                              ApprovalRecordRepository approvalRecordRepository,
                              AuditLogService auditLogService,
                              ArApService arApService,
                              DateValidator dateValidator,
                              ApprovalLevelResolver approvalLevelResolver) {
        this.returnRepository = returnRepository;
        this.returnItemRepository = returnItemRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryItemRepository = deliveryItemRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
        this.stockRepository = stockRepository;
        this.ledger = ledger;
        this.unqualifiedService = unqualifiedService;
        this.approvalRecordRepository = approvalRecordRepository;
        this.auditLogService = auditLogService;
        this.arApService = arApService;
        this.dateValidator = dateValidator;
        this.approvalLevelResolver = approvalLevelResolver;
    }

    @Transactional
    public SalesReturnVO create(SalesReturnCreateRequest req) {
        if (req == null || req.deliveryId() == null) {
            throw BusinessException.badRequest("原销售出库单不能为空");
        }
        dateValidator.validateBizDate(req.returnDate(), "退货日期");
        // P3：建退货先锁源出库单头，避免与 voidDelivery 并发时建出落在已 VOID 出库单上的滞留退货单
        SalesDelivery delivery = deliveryRepository.findByIdForUpdate(req.deliveryId())
                .orElseThrow(() -> BusinessException.notFound("销售出库单不存在: " + req.deliveryId()));
        // 批次十四：出库改为审批后执行，已完成出库单状态为 COMPLETED（历史单据 ACTIVE）
        // 锁内复核源单状态：作废/未完成的出库单不可退货（沿用既有文案）
        if (!"ACTIVE".equals(delivery.getStatus()) && !"COMPLETED".equals(delivery.getStatus())) {
            throw BusinessException.badRequest("已作废或未完成的出库单不可退货: " + delivery.getDeliveryNo());
        }
        SalesOrder order = orderRepository.findById(delivery.getOrderId())
                .orElseThrow(() -> BusinessException.notFound("销售订单不存在: " + delivery.getOrderId()));

        List<SalesReturnItemRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("退货明细不能为空");
        }

        Map<String, BigDecimal> deliveredQty = deliveryItemRepository.findByDeliveryId(delivery.getId()).stream()
                .collect(Collectors.toMap(
                        i -> key(i.getProductId(), i.getBatchNo()), SalesDeliveryItem::getQuantity,
                        BigDecimal::add));
        Map<String, BigDecimal> returnedQty = new HashMap<>();
        List<Long> returnIds = returnRepository.findByDeliveryId(delivery.getId()).stream()
                .filter(r -> !"VOID".equals(r.getStatus()))
                .map(SalesReturn::getId).toList();
        if (!returnIds.isEmpty()) {
            for (SalesReturnItem it : returnItemRepository.findByReturnIdIn(returnIds)) {
                returnedQty.merge(key(it.getProductId(), it.getBatchNo()), it.getQuantity(), BigDecimal::add);
            }
        }
        Map<Long, BigDecimal> priceByProduct = orderItemRepository.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(SalesOrderItem::getProductId, SalesOrderItem::getUnitPrice,
                        (a, b) -> a));
        Map<String, BigDecimal> costByBatch = deliveryItemRepository.findByDeliveryId(delivery.getId()).stream()
                .collect(Collectors.toMap(
                        i -> key(i.getProductId(), i.getBatchNo()), SalesDeliveryItem::getUnitCost,
                        (a, b) -> a));

        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        List<SalesReturnItem> toSave = new ArrayList<>();
        for (SalesReturnItemRequest it : items) {
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
                throw BusinessException.badRequest("退回入库仓库不能为空");
            }
            Long locationId = ledger.requireLocationId(it.warehouseId());
            String k = key(it.productId(), it.batchNo());
            if (!seen.add(k)) {
                throw BusinessException.badRequest("退货明细重复: 商品 " + it.productId() + " 批号 " + it.batchNo());
            }
            if (!deliveredQty.containsKey(k)) {
                throw BusinessException.badRequest("该商品批次不在原出库单中: 商品 " + it.productId()
                        + " 批号 " + it.batchNo());
            }
            BigDecimal returnable = deliveredQty.get(k).subtract(returnedQty.getOrDefault(k, BigDecimal.ZERO));
            if (it.quantity().compareTo(returnable) > 0) {
                throw BusinessException.badRequest("退货数量超过可退数量: 批号 " + it.batchNo()
                        + " 可退 " + returnable.toPlainString() + " < 退货 " + it.quantity().toPlainString());
            }
            BigDecimal price = priceByProduct.getOrDefault(it.productId(), BigDecimal.ZERO);
            BigDecimal cost = costByBatch.getOrDefault(k, BigDecimal.ZERO);

            SalesReturnItem entity = new SalesReturnItem();
            entity.setProductId(it.productId());
            entity.setBatchNo(it.batchNo().trim());
            entity.setQuantity(it.quantity());
            entity.setUnitPrice(price);
            entity.setUnitCost(cost);
            entity.setWarehouseId(locationId);
            toSave.add(entity);
            total = total.add(it.quantity().multiply(price));
        }

        SalesReturn header = new SalesReturn();
        header.setReturnNo(ledger.genNo("SR"));
        header.setDeliveryId(delivery.getId());
        header.setCustomerId(order.getCustomerId());
        header.setStoreId(req.storeId());
        header.setReturnDate(req.returnDate() == null ? LocalDate.now() : req.returnDate());
        header.setStatus("DRAFT");
        header.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        header.setReason(req.reason());
        header.setCreatedBy(ledger.currentUser());
        header = returnRepository.save(header);

        List<SalesReturnItem> saved = new ArrayList<>();
        for (SalesReturnItem item : toSave) {
            item.setReturnId(header.getId());
            saved.add(returnItemRepository.save(item));
        }
        auditLogService.log(header.getCreatedBy(), "CREATE_SALES_RETURN", BIZ_TYPE, header.getId(),
                "创建销售退货单 " + header.getReturnNo() + "，金额 " + header.getTotalAmount().toPlainString(),
                null, AuditLogService.json("returnNo", header.getReturnNo(), "status", "DRAFT",
                        "totalAmount", header.getTotalAmount(), "deliveryId", header.getDeliveryId()));
        return toVO(header, saved, delivery.getDeliveryNo(), customerName(header.getCustomerId()));
    }

    @Transactional
    public SalesReturnVO submit(Long id) {
        // P0-2：提交先锁退货单头，避免并发重复提交生成多条 PENDING 待审记录
        SalesReturn header = returnRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("销售退货单不存在: " + id));
        // 退货不校验源单据/制单人归属：由「退货专员」角色（权限码）负责全仓退货单
        if (!"DRAFT".equals(header.getStatus()) && !"REJECTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅草稿或已驳回退货单可提交，当前状态: " + header.getStatus());
        }
        header.setStatus("SUBMITTED");
        returnRepository.save(header);

        ApprovalRecord record = new ApprovalRecord();
        record.setBizType(BIZ_TYPE);
        record.setBizId(header.getId());
        // P1-1 金额分级：凭退货总金额解析待审节点（L1/L2）
        record.setNode(approvalLevelResolver.node(BIZ_TYPE, header.getTotalAmount()));
        record.setStatus("PENDING");
        approvalRecordRepository.save(record);

        auditLogService.log(ledger.currentUser(), "SUBMIT_SALES_RETURN", BIZ_TYPE, header.getId(),
                "提交销售退货单 " + header.getReturnNo() + "，金额 " + header.getTotalAmount().toPlainString(),
                AuditLogService.json("returnNo", header.getReturnNo(), "status", "DRAFT"),
                AuditLogService.json("returnNo", header.getReturnNo(), "status", "SUBMITTED",
                        "node", record.getNode()));

        return toVO(header, returnItemRepository.findByReturnId(id),
                deliveryNo(header.getDeliveryId()), customerName(header.getCustomerId()));
    }

    /**
     * 验收：APPROVED 状态下逐明细给结论；合格回补库存并回冲成本，不合格登记隔离。
     * 完成后状态置 RECEIVED 并生成负数应收（红字）抵扣客户欠款。
     */
    @Transactional
    public SalesReturnVO receive(Long id, SalesReturnReceiveRequest req) {
        // P0-2：验收先锁退货单头，并发重复验收只有一次能通过 APPROVED 状态守卫（避免重复回补库存）
        SalesReturn header = returnRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("销售退货单不存在: " + id));
        // 验收同样不校验制单人：退货专员可验收他人提交的退货单
        if (!"APPROVED".equals(header.getStatus())) {
            throw BusinessException.badRequest("仅审批通过的退货单可验收，当前状态: " + header.getStatus());
        }
        // P0-2 源单复核：验收要按源出库单的批次回补库存，源单已作废/未执行则退货失去依据，直接 400。
        // 锁源单头，保证「复核 → 回补库存」期间源单不会被并发作废（与 voidDelivery 的单据头锁互斥）。
        SalesDelivery sourceDelivery = deliveryRepository.findByIdForUpdate(header.getDeliveryId())
                .orElseThrow(() -> BusinessException.notFound(
                        "销售出库单不存在: " + header.getDeliveryId()));
        if (!"ACTIVE".equals(sourceDelivery.getStatus()) && !"COMPLETED".equals(sourceDelivery.getStatus())) {
            throw BusinessException.badRequest("已作废或未完成的出库单不可退货: "
                    + sourceDelivery.getDeliveryNo());
        }
        List<SalesReturnItem> items = returnItemRepository.findByReturnId(id);
        if (items.isEmpty()) {
            throw BusinessException.badRequest("退货明细为空，无法验收");
        }
        Map<Long, SalesReturnReceiveItemRequest> decisions = new HashMap<>();
        if (req != null && req.items() != null) {
            for (SalesReturnReceiveItemRequest it : req.items()) {
                if (it.itemId() != null) {
                    decisions.put(it.itemId(), it);
                }
            }
        }

        String opUser = ledger.currentUser();
        for (SalesReturnItem item : items) {
            SalesReturnReceiveItemRequest decision = decisions.get(item.getId());
            boolean qualified = decision == null || decision.qualified() == null || decision.qualified();
            item.setAcceptResult(qualified ? "QUALIFIED" : "UNQUALIFIED");
            item.setRejectReason(decision == null ? null : decision.rejectReason());
            returnItemRepository.save(item);

            if (qualified) {
                BigDecimal qty = item.getQuantity();
                // 温控兼容（P2-9）：回补仓必须与商品存储条件匹配
                Product product = productRepository.findById(item.getProductId()).orElse(null);
                Warehouse warehouse = warehouseRepository.findById(item.getWarehouseId()).orElse(null);
                if (product != null && warehouse != null) {
                    TemperatureCompatibility.requireCompatible(product.getCode(), product.getStorageCond(),
                            warehouse.getTempType(), warehouse.getName(), "销售退货回补");
                }
                Stock existing = stockRepository.findByProductIdAndBatchNo(item.getProductId(), item.getBatchNo())
                        .stream().findFirst().orElse(null);
                if (existing == null) {
                    throw BusinessException.badRequest("原批次库存不存在，无法回补: 商品 "
                            + item.getProductId() + " 批号 " + item.getBatchNo());
                }
                Stock stock = ledger.upsertStock(item.getProductId(), item.getBatchNo(),
                        existing.getProduceDate(), existing.getExpireDate(), item.getWarehouseId(),
                        qty, item.getUnitCost());
                BigDecimal after = stock.getQuantity();
                BigDecimal before = after.subtract(qty);
                ledger.writeStockTransaction(item.getProductId(), item.getBatchNo(), OP_IN,
                        REF_SALES_RETURN, header.getId(), item.getWarehouseId(), qty, before, after, opUser);
                ledger.writeLotTrace(item.getProductId(), item.getBatchNo(), TRACE_SALES_RETURN_ITEM,
                        item.getId(), TRACE_STOCK, stock.getId(), qty, opUser);
                ledger.applyCostIn(item.getProductId(), item.getBatchNo(), qty, item.getUnitCost(),
                        ledger.currentBalance(item.getProductId()));
            } else {
                unqualifiedService.registerReturned(item.getProductId(), item.getWarehouseId(),
                        item.getBatchNo(), item.getQuantity(),
                        decision == null || decision.rejectReason() == null
                                ? "销售退货验收不合格" : decision.rejectReason());
            }
        }

        header.setStatus("RECEIVED");
        returnRepository.save(header);

        // 红字应收：负数金额冲减客户应收与信用占用
        arApService.generateAr(header.getCustomerId(), BIZ_TYPE, header.getId(),
                header.getTotalAmount().negate());

        auditLogService.log(opUser, "SALES_RETURN_RECEIVED", BIZ_TYPE, header.getId(),
                "销售退货验收完成 " + header.getReturnNo() + "，金额 " + header.getTotalAmount().toPlainString());

        return toVO(header, items, deliveryNo(header.getDeliveryId()), customerName(header.getCustomerId()));
    }

    /**
     * 销售退货作废（批次十三）：状态置 VOID，不物理删除。
     * 已验收（RECEIVED）的单据需回冲库存（合格明细退回出库 + 成本台出账）并作废原红字应收；
     * 未验收单据仅改状态。待审批（SUBMITTED）单据需先驳回/审批完成，避免遗留待办审批记录。
     * 不校验制单人：由 sales:return:void 权限码控制（退货专员可作废他人单据）。
     */
    @Transactional
    public SalesReturnVO voidReturn(Long id, String reason) {
        // P0-2：作废先锁退货单头，避免与并发验收/作废交叉覆盖状态并重复回冲出库库存
        SalesReturn header = returnRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("销售退货单不存在: " + id));
        if ("VOID".equals(header.getStatus())) {
            throw BusinessException.badRequest("销售退货单已作废: " + header.getReturnNo());
        }
        if ("SUBMITTED".equals(header.getStatus())) {
            throw BusinessException.badRequest("待审批退货单不可直接作废，请先驳回或完成审批: "
                    + header.getReturnNo());
        }
        String opUser = ledger.currentUser();
        List<SalesReturnItem> items = returnItemRepository.findByReturnId(id);
        boolean received = "RECEIVED".equals(header.getStatus());
        if (received) {
            for (SalesReturnItem item : items) {
                // 不合格明细验收时未回补库存（登记隔离），作废不涉及库存回冲
                if (!"QUALIFIED".equals(item.getAcceptResult())) {
                    continue;
                }
                ledger.outboundBatch(item.getProductId(), item.getBatchNo(), item.getWarehouseId(),
                        item.getQuantity(), opUser, REF_SALES_RETURN, header.getId());
                stockRepository.findByProductIdAndBatchNoAndLocationId(item.getProductId(),
                                item.getBatchNo(), item.getWarehouseId())
                        .ifPresent(s -> ledger.writeLotTrace(item.getProductId(), item.getBatchNo(),
                                TRACE_SALES_RETURN_VOID, header.getId(), TRACE_STOCK, s.getId(),
                                item.getQuantity(), opUser));
            }
            // 冲回原红字应收
            arApService.voidByBiz(REF_SALES_RETURN, header.getId());
        }

        header.setStatus("VOID");
        returnRepository.save(header);

        auditLogService.log(opUser, "VOID_SALES_RETURN", BIZ_TYPE, header.getId(),
                "作废销售退货单 " + header.getReturnNo() + (reason == null || reason.isBlank()
                        ? "" : "，原因：" + reason.trim()),
                AuditLogService.json("returnNo", header.getReturnNo(), "status", received ? "RECEIVED" : "DRAFT",
                        "totalAmount", header.getTotalAmount(), "itemCount", items.size()),
                AuditLogService.json("returnNo", header.getReturnNo(), "status", "VOID",
                        "totalAmount", header.getTotalAmount(), "itemCount", items.size(), "reason", reason));

        return toVO(header, items, deliveryNo(header.getDeliveryId()), customerName(header.getCustomerId()));
    }

    /**
     * 列表：不按 createdBy 隔离（退货专员可见全仓退货单），可见性由 sales:return:view 权限码控制。
     */
    public PageResult<SalesReturnVO> list(String keyword, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<SalesReturn> result = returnRepository.search(
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                status == null || status.isBlank() ? null : status.trim(),
                null, pageable);

        List<Long> ids = result.getContent().stream().map(SalesReturn::getId).toList();
        Map<Long, List<SalesReturnItem>> itemMap = returnItemRepository.findByReturnIdIn(ids).stream()
                .collect(Collectors.groupingBy(SalesReturnItem::getReturnId));
        Map<Long, String> productNames = productNames(itemMap.values().stream().flatMap(List::stream)
                .map(SalesReturnItem::getProductId).toList());
        Map<Long, String> deliveryNos = deliveryRepository
                .findAllById(result.getContent().stream().map(SalesReturn::getDeliveryId).toList()).stream()
                .collect(Collectors.toMap(SalesDelivery::getId, SalesDelivery::getDeliveryNo));
        Map<Long, String> customerNames = customerNames(result.getContent().stream()
                .map(SalesReturn::getCustomerId).toList());

        List<SalesReturnVO> records = result.getContent().stream()
                .map(h -> toVOWithNames(h, itemMap.getOrDefault(h.getId(), List.of()),
                        deliveryNos.get(h.getDeliveryId()), customerNames.get(h.getCustomerId()), productNames))
                .toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    /** 销售退货详情：含明细、原出库单号与客户名；不存在 404。 */
    public SalesReturnVO get(Long id) {
        SalesReturn header = returnRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("销售退货单不存在: " + id));
        return toVO(header, returnItemRepository.findByReturnId(id),
                deliveryNo(header.getDeliveryId()), customerName(header.getCustomerId()));
    }

    /**
     * 导出用全量查询（上限 {@link ExportWriter#MAX_ROWS}）：复用列表的查询条件，不分页。
     */
    public ExportPayload<SalesReturnExportRow> exportRows(String keyword, String status) {
        Pageable pageable = PageRequest.of(0, ExportWriter.MAX_ROWS, Sort.by(Sort.Direction.DESC, "id"));
        Page<SalesReturn> result = returnRepository.search(
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                status == null || status.isBlank() ? null : status.trim(),
                null, pageable);
        Map<Long, String> customerNames = customerNames(result.getContent().stream()
                .map(SalesReturn::getCustomerId).toList());
        List<SalesReturnExportRow> rows = result.getContent().stream()
                .map(h -> new SalesReturnExportRow(h.getReturnNo(), billDate(h.getReturnDate()),
                        customerNames.get(h.getCustomerId()), h.getTotalAmount(), h.getStatus(), h.getCreatedBy()))
                .toList();
        return new ExportPayload<>(rows, result.getTotalElements(), ExportWriter.MAX_ROWS);
    }

    private static String billDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    private SalesReturnVO toVO(SalesReturn header, List<SalesReturnItem> items,
                               String deliveryNo, String customerName) {
        Map<Long, String> names = productNames(items.stream().map(SalesReturnItem::getProductId).toList());
        return toVOWithNames(header, items, deliveryNo, customerName, names);
    }

    private SalesReturnVO toVOWithNames(SalesReturn header, List<SalesReturnItem> items, String deliveryNo,
                                        String customerName, Map<Long, String> productNames) {
        List<SalesReturnItemVO> vos = items.stream()
                .map(i -> new SalesReturnItemVO(i.getId(), i.getProductId(), productNames.get(i.getProductId()),
                        i.getBatchNo(), i.getQuantity(), i.getUnitPrice(), i.getUnitCost(),
                        i.getWarehouseId(), i.getAcceptResult(), i.getRejectReason()))
                .toList();
        return new SalesReturnVO(header.getId(), header.getReturnNo(), header.getDeliveryId(), deliveryNo,
                header.getCustomerId(), customerName, header.getStoreId(), header.getReturnDate(),
                header.getStatus(), header.getTotalAmount(), header.getReason(), header.getCreatedBy(),
                header.getCreatedAt(), vos);
    }

    private String deliveryNo(Long deliveryId) {
        return deliveryRepository.findById(deliveryId).map(SalesDelivery::getDeliveryNo).orElse(null);
    }

    private String customerName(Long customerId) {
        return customerRepository.findById(customerId).map(Customer::getName).orElse(null);
    }

    private Map<Long, String> customerNames(List<Long> ids) {
        Set<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return customerRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getName));
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

    private static String key(Long productId, String batchNo) {
        return productId + "#" + (batchNo == null ? "" : batchNo.trim());
    }
}
