package com.pharma.inventory.service;

import com.pharma.common.BusinessException;
import com.pharma.common.DataScope;
import com.pharma.common.PageResult;
import com.pharma.inventory.dto.*;
import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.entity.Stocktake;
import com.pharma.inventory.entity.StocktakeItem;
import com.pharma.inventory.repository.StockRepository;
import com.pharma.inventory.repository.StocktakeItemRepository;
import com.pharma.inventory.repository.StocktakeRepository;
import com.pharma.system.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.pharma.inventory.service.InventoryLedgerService.OP_ADJUST;
import static com.pharma.inventory.service.InventoryLedgerService.REF_STOCKTAKE;

/**
 * 盘点服务：create 记账面/实盘/盈亏，submit 生成盘盈盘亏调整（ADJUST 流水 + 库存 upsert）。
 */
@Service
public class StocktakeService {

    private final StocktakeRepository stocktakeRepository;
    private final StocktakeItemRepository stocktakeItemRepository;
    private final StockRepository stockRepository;
    private final InventoryLedgerService ledger;
    private final AuditLogService auditLogService;

    public StocktakeService(StocktakeRepository stocktakeRepository,
                            StocktakeItemRepository stocktakeItemRepository,
                            StockRepository stockRepository,
                            InventoryLedgerService ledger,
                            AuditLogService auditLogService) {
        this.stocktakeRepository = stocktakeRepository;
        this.stocktakeItemRepository = stocktakeItemRepository;
        this.stockRepository = stockRepository;
        this.ledger = ledger;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public StocktakeVO create(StocktakeCreateRequest req) {
        Long locationId = ledger.requireLocationId(req.warehouseId());
        List<StocktakeItemRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("盘点明细不能为空");
        }

        Stocktake stocktake = new Stocktake();
        stocktake.setStocktakeNo(ledger.genNo("ST"));
        stocktake.setWarehouseId(locationId);
        stocktake.setStatus("DRAFT");
        stocktake.setCreatedBy(ledger.currentUser());
        stocktake = stocktakeRepository.save(stocktake);

        List<StocktakeItemVO> vos = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StocktakeItemRequest it : items) {
            if (it.productId() == null) {
                throw BusinessException.badRequest("商品不能为空");
            }
            if (it.batchNo() == null || it.batchNo().isBlank()) {
                throw BusinessException.badRequest("批号不能为空");
            }
            BigDecimal actual = it.actualQty() == null ? BigDecimal.ZERO : it.actualQty();
            if (actual.compareTo(BigDecimal.ZERO) < 0) {
                throw BusinessException.badRequest("实盘数量不能为负");
            }
            if (!seen.add(it.productId() + ":" + it.batchNo())) {
                throw BusinessException.badRequest("盘点明细重复: " + it.productId() + "/" + it.batchNo());
            }
            Stock stock = stockRepository.findByProductIdAndBatchNoAndLocationId(
                    it.productId(), it.batchNo(), locationId).orElse(null);
            BigDecimal book = stock == null ? BigDecimal.ZERO : stock.getQuantity();
            BigDecimal diff = actual.subtract(book);

            StocktakeItem si = new StocktakeItem();
            si.setStocktakeId(stocktake.getId());
            si.setProductId(it.productId());
            si.setBatchNo(it.batchNo());
            si.setBookQty(book);
            si.setActualQty(actual);
            si.setDiffQty(diff);
            si.setProduceDate(it.produceDate());
            si.setExpireDate(it.expireDate());
            si = stocktakeItemRepository.save(si);
            vos.add(toItemVO(si));
        }
        return toVO(stocktake, vos);
    }

    /**
     * 提交盘点（P0-8）：对涉及批次加行级锁后**重读当前账面**，按 actual - bookNow 重算盈亏再调账。
     * create 时的 book_qty / diff_qty 只是创建时点快照，期间发生的出入库不会被旧 diff 覆盖；
     * 提交时点的账面与盈亏另存 committed_book_qty / committed_diff_qty 便于审计。
     */
    @Transactional
    public StocktakeVO submit(Long id) {
        Stocktake stocktake = stocktakeRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("盘点单不存在: " + id));
        // IDOR 防护：非 ADMIN/approval:view 只能提交自己创建的盘点单
        DataScope.requireOwnership(stocktake.getCreatedBy());
        if (!"DRAFT".equals(stocktake.getStatus())) {
            throw BusinessException.badRequest("仅草稿状态可提交盘点，当前状态: " + stocktake.getStatus());
        }

        Long locationId = stocktake.getWarehouseId();
        String opUser = ledger.currentUser();
        List<StocktakeItem> items = new ArrayList<>(stocktakeItemRepository.findByStocktakeId(id));
        // 统一加锁顺序（商品 → 批号），避免多明细并发盘点互相死锁
        items.sort(Comparator.comparing(StocktakeItem::getProductId)
                .thenComparing(StocktakeItem::getBatchNo));

        int profitLines = 0;
        int lossLines = 0;
        BigDecimal netDiff = BigDecimal.ZERO;

        for (StocktakeItem si : items) {
            Stock existing = stockRepository.findByProductIdAndBatchNoAndLocationIdForUpdate(
                    si.getProductId(), si.getBatchNo(), locationId).orElse(null);
            BigDecimal bookNow = existing == null ? BigDecimal.ZERO : existing.getQuantity();
            BigDecimal diff = si.getActualQty().subtract(bookNow);

            si.setCommittedBookQty(bookNow);
            si.setCommittedDiffQty(diff);
            stocktakeItemRepository.save(si);

            if (diff.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            if (diff.signum() > 0) {
                profitLines++;
            } else {
                lossLines++;
            }
            netDiff = netDiff.add(diff);

            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                // 盘盈
                if (existing == null) {
                    // 新批次：需提供效期才能新建库存（缺效期返回 400）
                    if (si.getExpireDate() == null) {
                        throw BusinessException.badRequest(
                                "新批次盘点缺效期，无法建立库存: " + si.getBatchNo());
                    }
                    BigDecimal unitCost = ledger.productWac(si.getProductId());
                    Stock created = ledger.upsertStock(si.getProductId(), si.getBatchNo(),
                            si.getProduceDate(), si.getExpireDate(), locationId, diff, unitCost);
                    ledger.writeStockTransaction(si.getProductId(), si.getBatchNo(), OP_ADJUST, REF_STOCKTAKE,
                            stocktake.getId(), locationId, diff, BigDecimal.ZERO, created.getQuantity(), opUser);
                    ledger.applyCostIn(si.getProductId(), si.getBatchNo(), diff,
                            unitCost, ledger.currentBalance(si.getProductId()));
                    continue;
                }
                BigDecimal before = existing.getQuantity();
                BigDecimal after = before.add(diff);
                existing.setQuantity(after);
                stockRepository.save(existing);

                ledger.writeStockTransaction(si.getProductId(), si.getBatchNo(), OP_ADJUST, REF_STOCKTAKE,
                        stocktake.getId(), locationId, diff, before, after, opUser);
                ledger.applyCostIn(si.getProductId(), si.getBatchNo(), diff,
                        existing.getUnitCost(), ledger.currentBalance(si.getProductId()));
            } else {
                // 盘亏：diff = actual - bookNow，actual >= 0，故扣减量必然不超过当前账面
                BigDecimal reduce = diff.abs();
                BigDecimal before = existing.getQuantity();
                BigDecimal after = before.subtract(reduce);
                existing.setQuantity(after);
                stockRepository.save(existing);

                ledger.writeStockTransaction(si.getProductId(), si.getBatchNo(), OP_ADJUST, REF_STOCKTAKE,
                        stocktake.getId(), locationId, diff.negate(), before, after, opUser);
                ledger.applyCostOut(si.getProductId(), si.getBatchNo(), reduce,
                        existing.getUnitCost(), ledger.currentBalance(si.getProductId()));
            }
        }

        stocktake.setStatus("COMPLETED");
        stocktakeRepository.save(stocktake);

        auditLogService.log(opUser, "STOCKTAKE_SUBMIT", REF_STOCKTAKE, stocktake.getId(),
                "提交盘点 " + stocktake.getStocktakeNo() + "，盘盈 " + profitLines + " 行、盘亏 " + lossLines
                        + " 行，净差异 " + netDiff,
                AuditLogService.json("stocktakeNo", stocktake.getStocktakeNo(), "status", "DRAFT"),
                AuditLogService.json("stocktakeNo", stocktake.getStocktakeNo(), "status", "COMPLETED",
                        "profitLines", profitLines, "lossLines", lossLines, "netQtyDiff", netDiff));

        List<StocktakeItemVO> vos = items.stream().map(this::toItemVO).toList();
        return toVO(stocktake, vos);
    }

    public PageResult<StocktakeVO> list(String keyword, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<Stocktake> result = stocktakeRepository.search(keyword, pageable);

        List<Long> ids = result.getContent().stream().map(Stocktake::getId).toList();
        Map<Long, List<StocktakeItem>> itemMap = stocktakeItemRepository.findByStocktakeIdIn(ids).stream()
                .collect(Collectors.groupingBy(StocktakeItem::getStocktakeId));

        List<StocktakeVO> records = result.getContent().stream()
                .map(t -> toVO(t, itemMap.getOrDefault(t.getId(), List.of())
                        .stream().map(this::toItemVO).toList()))
                .toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    /** 已提交的盘点返回提交时点账面/盈亏（未提交则返回创建时点快照） */
    private StocktakeItemVO toItemVO(StocktakeItem si) {
        BigDecimal book = si.getCommittedBookQty() != null ? si.getCommittedBookQty() : si.getBookQty();
        BigDecimal diff = si.getCommittedDiffQty() != null ? si.getCommittedDiffQty() : si.getDiffQty();
        return new StocktakeItemVO(si.getId(), si.getProductId(), si.getBatchNo(),
                book, si.getActualQty(), diff,
                si.getProduceDate(), si.getExpireDate());
    }

    private StocktakeVO toVO(Stocktake stocktake, List<StocktakeItemVO> items) {
        return new StocktakeVO(stocktake.getId(), stocktake.getStocktakeNo(), stocktake.getWarehouseId(),
                stocktake.getStatus(), stocktake.getCreatedBy(), stocktake.getCreatedAt(), items);
    }
}
