package com.pharma.inventory.service;

import com.pharma.base.entity.Product;
import com.pharma.base.entity.Warehouse;
import com.pharma.base.repository.ProductRepository;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.common.BusinessException;
import com.pharma.inventory.entity.CostLedger;
import com.pharma.inventory.entity.LotTrace;
import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.entity.StockTransaction;
import com.pharma.inventory.repository.CostLedgerRepository;
import com.pharma.inventory.repository.LotTraceRepository;
import com.pharma.inventory.repository.StockRepository;
import com.pharma.inventory.repository.StockTransactionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 库存账务核心服务：批次库存 upsert、移动加权成本、FEFO 出库分配、三张流水写入。
 * 全部方法在调用方事务内执行（append-only 语义由上游 @Transactional 保证）。
 */
@Service
public class InventoryLedgerService {

    /** 近效期阻断阈值（全局常量，默认 30 天） */
    public static final int NEAR_EXPIRY_DAYS = 30;

    public static final String OP_IN = "IN";
    public static final String OP_OUT = "OUT";
    public static final String OP_ADJUST = "ADJUST";
    public static final String REF_PURCHASE_RECEIVE = "PURCHASE_RECEIVE";
    public static final String REF_SALES_DELIVERY = "SALES_DELIVERY";
    public static final String REF_STOCKTAKE = "STOCKTAKE";
    public static final String REF_UNQUALIFIED = "UNQUALIFIED";
    public static final String REF_SALES_RETURN = "SALES_RETURN";
    public static final String REF_PURCHASE_RETURN = "PURCHASE_RETURN";
    public static final String REF_TRANSFER = "TRANSFER";
    public static final String TRACE_STOCK = "STOCK";
    public static final String TRACE_PURCHASE_RECEIVE_ITEM = "PURCHASE_RECEIVE_ITEM";
    public static final String TRACE_SALES_DELIVERY_ITEM = "SALES_DELIVERY_ITEM";
    public static final String TRACE_PURCHASE_RECEIVE_VOID = "PURCHASE_RECEIVE_VOID";
    public static final String TRACE_SALES_DELIVERY_VOID = "SALES_DELIVERY_VOID";
    public static final String TRACE_SALES_RETURN_ITEM = "SALES_RETURN_ITEM";
    public static final String TRACE_SALES_RETURN_VOID = "SALES_RETURN_VOID";
    public static final String TRACE_PURCHASE_RETURN_ITEM = "PURCHASE_RETURN_ITEM";
    public static final String TRACE_TRANSFER_ITEM = "TRANSFER_ITEM";
    public static final String TRACE_TRANSFER_VOID = "TRANSFER_VOID";
    public static final String TRACE_PURCHASE_RETURN_VOID = "PURCHASE_RETURN_VOID";

    private final StockRepository stockRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final LotTraceRepository lotTraceRepository;
    private final CostLedgerRepository costLedgerRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final JdbcTemplate jdbc;

    public InventoryLedgerService(StockRepository stockRepository,
                                  StockTransactionRepository stockTransactionRepository,
                                  LotTraceRepository lotTraceRepository,
                                  CostLedgerRepository costLedgerRepository,
                                  WarehouseRepository warehouseRepository,
                                  ProductRepository productRepository,
                                  JdbcTemplate jdbc) {
        this.stockRepository = stockRepository;
        this.stockTransactionRepository = stockTransactionRepository;
        this.lotTraceRepository = lotTraceRepository;
        this.costLedgerRepository = costLedgerRepository;
        this.warehouseRepository = warehouseRepository;
        this.productRepository = productRepository;
        this.jdbc = jdbc;
    }

    /** 商品级移动加权余额（数量/金额） */
    public record Balance(BigDecimal qty, BigDecimal amount) {
        public static Balance zero() {
            return new Balance(BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    /** 出库批次分配结果 */
    public record BatchAllocation(String batchNo, BigDecimal quantity, BigDecimal unitCost, Long stockId) {
    }

    public String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "system";
        }
        return auth.getName();
    }

    /**
     * 单据号：number_sequence 序列表原子自增（首次 INSERT，其后 ON CONFLICT 累加），
     * 格式 前缀 + yyyyMMdd + 6 位流水，替代原「时间戳+随机」的碰撞风险实现（P1-20）。
     */
    public String genNo(String prefix) {
        Long seq = jdbc.queryForObject("""
                INSERT INTO number_sequence (prefix, seq) VALUES (?, 1)
                ON CONFLICT (prefix) DO UPDATE SET seq = number_sequence.seq + 1
                RETURNING seq
                """, Long.class, prefix);
        String date = LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        return prefix + date + String.format("%06d", seq == null ? 1L : seq);
    }

    /** 默认仓库：一仓一位，取 id 最小的启用仓库作为库存位置 */
    public Long defaultLocationId() {
        return warehouseRepository.findAll().stream()
                .filter(w -> !"INACTIVE".equals(w.getStatus()))
                .map(Warehouse::getId)
                .min(Long::compareTo)
                .orElseThrow(() -> BusinessException.badRequest("未找到仓库，请先创建仓库"));
    }

    /** 显式指定仓库：校验存在且未停用，返回其 id（作为库存位置 locationId） */
    public Long requireLocationId(Long warehouseId) {
        if (warehouseId == null) {
            throw BusinessException.badRequest("仓库不能为空");
        }
        return warehouseRepository.findById(warehouseId)
                .map(w -> {
                    if ("INACTIVE".equals(w.getStatus())) {
                        throw BusinessException.badRequest("仓库已停用，不可用于出入库: " + w.getName());
                    }
                    return w.getId();
                })
                .orElseThrow(() -> BusinessException.badRequest("仓库不存在: " + warehouseId));
    }

    /** 商品级移动加权单价（基于 cost_ledger 最近一条余额） */
    public BigDecimal productWac(Long productId) {
        Balance b = currentBalance(productId);
        if (b.qty().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return b.amount().divide(b.qty(), 6, RoundingMode.HALF_UP);
    }

    /** 当前商品级余额 */
    public Balance currentBalance(Long productId) {
        return costLedgerRepository.findFirstByProductIdOrderByIdDesc(productId)
                .map(c -> new Balance(c.getBalanceQty(), c.getBalanceAmount()))
                .orElse(Balance.zero());
    }

    /**
     * R2 效期阻断：已过期（expire_date < 今天）或近效期（expire_date - 今天 <= 阈值）→ 不可售。
     * 可售条件：expire_date > 今天 + 阈值（严格大于）。判定委托 {@link FefoAllocator}（R2 测试依赖本方法）。
     */
    public boolean isSellable(LocalDate expireDate) {
        return isSellable(expireDate, LocalDate.now(), NEAR_EXPIRY_DAYS);
    }

    public boolean isSellable(LocalDate expireDate, LocalDate today) {
        return isSellable(expireDate, today, NEAR_EXPIRY_DAYS);
    }

    public boolean isSellable(LocalDate expireDate, LocalDate today, int nearExpiryDays) {
        return FefoAllocator.isSellable(expireDate, today, nearExpiryDays);
    }

    /** 品种级近效期阈值：商品 near_expiry_days 非空用品种值，否则用全局 30 天 */
    public int nearExpiryThreshold(Long productId) {
        return productRepository.findById(productId)
                .map(this::nearExpiryThreshold)
                .orElse(NEAR_EXPIRY_DAYS);
    }

    /** 品种级近效期阈值（已取到商品时复用，避免二次查库） */
    public int nearExpiryThreshold(Product product) {
        if (product == null) {
            return NEAR_EXPIRY_DAYS;
        }
        return product.getNearExpiryDays() == null ? NEAR_EXPIRY_DAYS : product.getNearExpiryDays();
    }

    /**
     * 入库：upsert 批次库存，同批次多次入库取加权平均批次成本。
     * 并发安全（P1-20）：已存在批次先加行级悲观锁再累加；不存在时用 INSERT ... ON CONFLICT DO NOTHING
     * 原子占位，冲突则回到锁读累加，避免并发首建撞唯一约束 500。
     */
    public Stock upsertStock(Long productId, String batchNo, LocalDate produceDate, LocalDate expireDate,
                             Long locationId, BigDecimal qty, BigDecimal unitCost) {
        Stock stock = stockRepository
                .findByProductIdAndBatchNoAndLocationIdForUpdate(productId, batchNo, locationId)
                .orElse(null);
        if (stock == null) {
            int inserted = stockRepository.insertIfAbsent(productId, batchNo, produceDate, expireDate,
                    locationId, qty, unitCost);
            Stock created = stockRepository
                    .findByProductIdAndBatchNoAndLocationIdForUpdate(productId, batchNo, locationId)
                    .orElseThrow(() -> BusinessException.badRequest(
                            "库存批次创建失败: " + productId + "/" + batchNo));
            if (inserted == 1) {
                return created;
            }
            stock = created;
        }

        BigDecimal totalQty = stock.getQuantity().add(qty);
        BigDecimal totalAmount = stock.getQuantity().multiply(stock.getUnitCost())
                .add(qty.multiply(unitCost));
        stock.setQuantity(totalQty);
        stock.setUnitCost(totalAmount.divide(totalQty, 2, RoundingMode.HALF_UP));
        // 同批次默认同效期，保留原生产/效期
        return stockRepository.save(stock);
    }

    /** 写库存流水（append-only），locationId 记录发生仓库（P1-25） */
    public void writeStockTransaction(Long productId, String batchNo, String opType, String refType, Long refId,
                                      Long locationId, BigDecimal signedQty, BigDecimal beforeQty,
                                      BigDecimal afterQty, String opUser) {
        StockTransaction t = new StockTransaction();
        t.setProductId(productId);
        t.setBatchNo(batchNo);
        t.setLocationId(locationId);
        t.setOpType(opType);
        t.setRefType(refType);
        t.setRefId(refId);
        t.setQuantity(signedQty);
        t.setBeforeQty(beforeQty);
        t.setAfterQty(afterQty);
        t.setOpUser(opUser);
        stockTransactionRepository.save(t);
    }

    /** 写批次追溯（append-only） */
    public void writeLotTrace(Long productId, String batchNo, String sourceType, Long sourceId,
                              String targetType, Long targetId, BigDecimal qty, String opUser) {
        LotTrace lt = new LotTrace();
        lt.setProductId(productId);
        lt.setBatchNo(batchNo);
        lt.setSourceType(sourceType);
        lt.setSourceId(sourceId);
        lt.setTargetType(targetType);
        lt.setTargetId(targetId);
        lt.setQuantity(qty);
        lt.setOpUser(opUser);
        lotTraceRepository.save(lt);
    }

    /** 成本台账入库（append-only），返回新余额 */
    public Balance applyCostIn(Long productId, String batchNo, BigDecimal qty, BigDecimal unitCost, Balance current) {
        BigDecimal amount = qty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
        Balance next = new Balance(current.qty().add(qty), current.amount().add(amount));
        saveCostLedger(productId, batchNo, OP_IN, qty, unitCost, amount, next);
        return next;
    }

    /** 成本台账出库（append-only），按移动加权单价结转，返回新余额 */
    public Balance applyCostOut(Long productId, String batchNo, BigDecimal qty, BigDecimal unitCost, Balance current) {
        BigDecimal amount = qty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
        Balance next = new Balance(current.qty().subtract(qty), current.amount().subtract(amount));
        saveCostLedger(productId, batchNo, OP_OUT, qty, unitCost, amount.negate(), next);
        return next;
    }

    private void saveCostLedger(Long productId, String batchNo, String opType, BigDecimal qty,
                                BigDecimal unitCost, BigDecimal amount, Balance balance) {
        CostLedger c = new CostLedger();
        c.setProductId(productId);
        c.setBatchNo(batchNo);
        c.setOpType(opType);
        c.setQuantity(qty);
        c.setUnitCost(unitCost);
        c.setAmount(amount);
        c.setBalanceQty(balance.qty());
        c.setBalanceAmount(balance.amount());
        costLedgerRepository.save(c);
    }

    /**
     * R1 FEFO + R2 效期阻断 + R5 库存不可为负 的出库分配。
     * 对指定仓库内该商品全部批次加悲观锁，按 expire_date 升序、batch_no 升序依次扣减；
     * 跳过不可售批次；总量不足则整体拒绝（400）。FEFO 仅在指定仓库内进行，不跨仓库。
     */
    public List<BatchAllocation> allocateOutbound(Long productId, BigDecimal qty, Long locationId, String opUser,
                                                  String refType, Long refId) {
        return allocateOutbound(productId, qty, locationId, opUser, refType, refId,
                nearExpiryThreshold(productId));
    }

    /**
     * R1 FEFO + R2 效期阻断 + R5 库存不可为负 的出库分配。
     * 对指定仓库内该商品全部批次加悲观锁，按 expire_date 升序、batch_no 升序依次扣减；
     * 跳过不可售批次；总量不足则整体拒绝（400）。FEFO 仅在指定仓库内进行，不跨仓库。
     */
    public List<BatchAllocation> allocateOutbound(Long productId, BigDecimal qty, Long locationId, String opUser,
                                                  String refType, Long refId, int nearExpiryDays) {
        List<Stock> batches = stockRepository.findByProductIdAndLocationIdForUpdate(productId, locationId);
        FefoAllocator.FefoPlan plan = FefoAllocator.plan(batches, qty, nearExpiryDays, LocalDate.now());
        if (plan.blocked()) {
            throw BusinessException.badRequest(
                    "库存不足：商品 " + productId + " 可售批次总量 " + plan.totalSellable().toPlainString()
                            + " < 出库量 " + qty.toPlainString());
        }

        BigDecimal wac = productWac(productId);
        Balance balance = currentBalance(productId);
        java.util.Map<Long, Stock> byId = new java.util.HashMap<>();
        for (Stock s : batches) {
            byId.put(s.getId(), s);
        }

        List<BatchAllocation> result = new ArrayList<>();
        for (FefoAllocator.FefoLine line : plan.lines()) {
            Stock s = byId.get(line.stockId());
            if (s == null) {
                continue;
            }
            BigDecimal take = line.takeQty();
            BigDecimal before = s.getQuantity();
            BigDecimal after = before.subtract(take);
            s.setQuantity(after);
            stockRepository.save(s);

            writeStockTransaction(productId, s.getBatchNo(), OP_OUT, refType, refId,
                    locationId, take.negate(), before, after, opUser);
            balance = applyCostOut(productId, s.getBatchNo(), take, wac, balance);

            result.add(new BatchAllocation(s.getBatchNo(), take, wac, s.getId()));
        }
        return result;
    }

    /**
     * #13 事前批次分配预览：只读、不加锁（非锁查询），返回 FEFO 计划供前端展示。
     * 与 {@link #allocateOutbound} 共用 {@link FefoAllocator#plan}，保证预览与真实出库口径一致。
     */
    public FefoAllocator.FefoPlan planAllocation(Long productId, BigDecimal qty, Long locationId, int nearExpiryDays) {
        List<Stock> batches = stockRepository.findByProductIdAndLocationId(productId, locationId);
        return FefoAllocator.plan(batches, qty, nearExpiryDays, LocalDate.now());
    }

    /**
     * 指定批次精确出库扣减（P1-11 采购退货 / P1-12 调拨出仓共用）：
     * 行级锁 + 库存不足拒绝（400）+ OUT 流水 + 成本台账，返回该批次成本单价。
     * 与 FEFO 分配不同，本方法只扣指定批号，不跨批次、不校验效期（退货/调拨为仓内移库语义）。
     */
    public BigDecimal outboundBatch(Long productId, String batchNo, Long locationId, BigDecimal qty,
                                    String opUser, String refType, Long refId) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("出库数量必须大于0");
        }
        Stock stock = stockRepository
                .findByProductIdAndBatchNoAndLocationIdForUpdate(productId, batchNo, locationId)
                .orElseThrow(() -> BusinessException.badRequest(
                        "指定批次不存在: 商品 " + productId + " 批号 " + batchNo + " 仓库 " + locationId));
        BigDecimal before = stock.getQuantity();
        if (before.compareTo(qty) < 0) {
            throw BusinessException.badRequest("批次库存不足: 批号 " + batchNo + " 现存 "
                    + before.toPlainString() + " < 出库 " + qty.toPlainString());
        }
        BigDecimal after = before.subtract(qty);
        stock.setQuantity(after);
        stockRepository.save(stock);

        writeStockTransaction(productId, batchNo, OP_OUT, refType, refId, locationId,
                qty.negate(), before, after, opUser);
        applyCostOut(productId, batchNo, qty, stock.getUnitCost(), currentBalance(productId));
        return stock.getUnitCost();
    }

    /** 调拨出仓：指定批次精确扣减（ref_type=TRANSFER）。 */
    public BigDecimal transferOutbound(Long productId, String batchNo, Long fromLocationId, BigDecimal qty,
                                       String opUser, Long transferId) {
        return outboundBatch(productId, batchNo, fromLocationId, qty, opUser, REF_TRANSFER, transferId);
    }
}
