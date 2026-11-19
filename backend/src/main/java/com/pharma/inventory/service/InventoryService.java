package com.pharma.inventory.service;

import com.pharma.base.entity.Product;
import com.pharma.base.repository.ProductRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.DataScope;
import com.pharma.common.PageResult;
import com.pharma.inventory.dto.FefoPreviewVO;
import com.pharma.inventory.dto.StockVO;
import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.repository.StockRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 库存查询服务。
 * 成本脱敏（P1-23）：无 inventory:cost:view 权限者 unitCost 返回 null；
 * 商品名冗余返回（P1-30，批量取名避免 N+1）。
 */
@Service
public class InventoryService {

    private final StockRepository stockRepository;
    private final ProductRepository productRepository;
    private final InventoryLedgerService ledger;

    public InventoryService(StockRepository stockRepository, ProductRepository productRepository,
                            InventoryLedgerService ledger) {
        this.stockRepository = stockRepository;
        this.productRepository = productRepository;
        this.ledger = ledger;
    }

    public PageResult<StockVO> list(Long productId, String batchNo, Long warehouseId,
                                    LocalDate expireBefore, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.ASC, "id"));
        Page<Stock> result = stockRepository.search(productId, batchNo, warehouseId, expireBefore, pageable);
        List<StockVO> records = toVOs(result.getContent());
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    /**
     * 近效期预警：返回 quantity > 0 且 expire_date <= 今天+days 的批次（含已过期），
     * 按效期升序。
     */
    public List<StockVO> nearExpiry(int days) {
        int d = days <= 0 ? InventoryLedgerService.NEAR_EXPIRY_DAYS : days;
        return toVOs(stockRepository.findNearExpiry(LocalDate.now().plusDays(d)));
    }

    /**
     * #13 FEFO 事前批次预览（只读、无副作用）：
     * 校验数量 → 校验仓库（停用/不存在 400）→ 取商品与品种级阈值 → 生成分配计划 → 组装 VO。
     */
    public FefoPreviewVO fefoPreview(Long productId, Long warehouseId, BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("数量必须大于0");
        }
        if (productId == null) {
            throw BusinessException.badRequest("商品不能为空");
        }
        Long locationId = ledger.requireLocationId(warehouseId);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> BusinessException.badRequest("商品不存在: " + productId));
        int nearExpiryDays = ledger.nearExpiryThreshold(product);
        FefoAllocator.FefoPlan plan = ledger.planAllocation(productId, quantity, locationId, nearExpiryDays);
        String productName = product.getGenericName() == null ? "" : product.getGenericName();
        List<FefoPreviewVO.Batch> batches = plan.batches().stream()
                .map(b -> new FefoPreviewVO.Batch(b.batchNo(), b.availableQty(), b.plannedQty(),
                        b.expireDate(), b.daysToExpire(), b.expireStatus()))
                .toList();
        return new FefoPreviewVO(product.getId(), productName, plan.requestedQty(), nearExpiryDays,
                plan.totalSellable(), plan.blocked(), plan.blockReason(), batches);
    }

    private List<StockVO> toVOs(List<Stock> stocks) {
        Set<Long> productIds = stocks.stream().map(Stock::getProductId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = productIds.isEmpty() ? Map.of()
                : productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId,
                        p -> p.getGenericName() == null ? "" : p.getGenericName()));
        boolean costVisible = DataScope.canViewCost();
        return stocks.stream()
                .map(stock -> new StockVO(stock.getId(), stock.getProductId(),
                        names.get(stock.getProductId()), stock.getBatchNo(),
                        stock.getProduceDate(), stock.getExpireDate(), stock.getLocationId(),
                        stock.getQuantity(), costVisible ? stock.getUnitCost() : null))
                .toList();
    }
}
