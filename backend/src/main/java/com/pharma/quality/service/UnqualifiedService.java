package com.pharma.quality.service;

import com.pharma.base.entity.Product;
import com.pharma.base.entity.Warehouse;
import com.pharma.base.repository.ProductRepository;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.repository.StockRepository;
import com.pharma.inventory.service.InventoryLedgerService;
import com.pharma.quality.dto.UnqualifiedCreateRequest;
import com.pharma.quality.dto.UnqualifiedDisposeRequest;
import com.pharma.quality.dto.UnqualifiedVO;
import com.pharma.quality.entity.Unqualified;
import com.pharma.quality.repository.UnqualifiedRepository;
import com.pharma.system.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static com.pharma.inventory.service.InventoryLedgerService.OP_OUT;
import static com.pharma.inventory.service.InventoryLedgerService.REF_UNQUALIFIED;

/**
 * 不合格品管理：登记即从指定仓库 stock 隔离扣减（行级锁 + OUT 流水 + 成本台账），
 * 处理置 DISPOSED 并记录销毁方式/时间/操作人 + 审计日志（P1-17）。
 */
@Service
public class UnqualifiedService {

    private final UnqualifiedRepository repository;
    private final StockRepository stockRepository;
    private final ProductRepository productRepository;
    private final InventoryLedgerService ledger;
    private final AuditLogService auditLogService;
    private final WarehouseRepository warehouseRepository;

    public UnqualifiedService(UnqualifiedRepository repository,
                              StockRepository stockRepository,
                              ProductRepository productRepository,
                              InventoryLedgerService ledger,
                              AuditLogService auditLogService,
                              WarehouseRepository warehouseRepository) {
        this.repository = repository;
        this.stockRepository = stockRepository;
        this.productRepository = productRepository;
        this.ledger = ledger;
        this.auditLogService = auditLogService;
        this.warehouseRepository = warehouseRepository;
    }

    @Transactional
    public UnqualifiedVO register(UnqualifiedCreateRequest req) {
        if (req.productId() == null) {
            throw BusinessException.badRequest("商品不能为空");
        }
        if (req.warehouseId() == null) {
            throw BusinessException.badRequest("不合格品登记必须指定隔离仓库");
        }
        if (req.batchNo() == null || req.batchNo().isBlank()) {
            throw BusinessException.badRequest("批号不能为空");
        }
        if (req.quantity() == null || req.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("数量必须大于0");
        }

        Long locationId = ledger.requireLocationId(req.warehouseId());
        Stock stock = stockRepository
                .findByProductIdAndBatchNoAndLocationIdForUpdate(req.productId(), req.batchNo(), locationId)
                .orElse(null);
        BigDecimal available = stock == null ? BigDecimal.ZERO : stock.getQuantity();
        if (available.compareTo(req.quantity()) < 0) {
            throw BusinessException.badRequest("该仓库批次可用库存不足: 批号 " + req.batchNo()
                    + " 现存 " + available.toPlainString() + " < 登记数量 " + req.quantity().toPlainString());
        }

        String opUser = ledger.currentUser();
        Unqualified u = new Unqualified();
        u.setProductId(req.productId());
        u.setWarehouseId(locationId);
        u.setBatchNo(req.batchNo());
        u.setQuantity(req.quantity());
        u.setReason(req.reason());
        u.setStatus("PENDING");
        u.setCreatedBy(opUser);
        u = repository.save(u);

        BigDecimal before = stock.getQuantity();
        BigDecimal after = before.subtract(req.quantity());
        stock.setQuantity(after);
        stockRepository.save(stock);

        ledger.writeStockTransaction(req.productId(), req.batchNo(), OP_OUT, REF_UNQUALIFIED,
                u.getId(), locationId, req.quantity().negate(), before, after, opUser);
        ledger.applyCostOut(req.productId(), req.batchNo(), req.quantity(),
                stock.getUnitCost(), ledger.currentBalance(req.productId()));

        return toVO(u);
    }

    /**
     * 销售退货验收不合格登记（P1-11）：退货商品尚未入库，仅建立不合格品隔离记录，
     * 不做库存扣减（与 register 的“在库隔离扣减”语义区分）。
     */
    @Transactional
    public UnqualifiedVO registerReturned(Long productId, Long warehouseId, String batchNo,
                                          BigDecimal quantity, String reason) {
        if (productId == null) {
            throw BusinessException.badRequest("商品不能为空");
        }
        if (warehouseId == null) {
            throw BusinessException.badRequest("不合格品登记必须指定隔离仓库");
        }
        if (batchNo == null || batchNo.isBlank()) {
            throw BusinessException.badRequest("批号不能为空");
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("数量必须大于0");
        }
        Long locationId = ledger.requireLocationId(warehouseId);
        String opUser = ledger.currentUser();
        Unqualified u = new Unqualified();
        u.setProductId(productId);
        u.setWarehouseId(locationId);
        u.setBatchNo(batchNo);
        u.setQuantity(quantity);
        u.setReason(reason);
        u.setStatus("PENDING");
        u.setCreatedBy(opUser);
        u = repository.save(u);
        return toVO(u);
    }

    @Transactional
    public UnqualifiedVO dispose(Long id, UnqualifiedDisposeRequest req) {        Unqualified u = repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("不合格品记录不存在: " + id));
        if ("DISPOSED".equals(u.getStatus())) {
            throw BusinessException.badRequest("该不合格品已处理");
        }
        String method = req == null || req.disposeMethod() == null || req.disposeMethod().isBlank()
                ? "DESTROY" : req.disposeMethod().trim();
        String opUser = ledger.currentUser();
        String supervisor = req == null || req.disposedBy() == null || req.disposedBy().isBlank()
                ? opUser : req.disposedBy().trim();
        LocalDateTime disposedAt = req == null || req.disposedAt() == null
                ? LocalDateTime.now() : req.disposedAt().atStartOfDay();

        u.setStatus("DISPOSED");
        u.setDisposeMethod(method);
        u.setDisposedAt(disposedAt);
        u.setDisposedBy(supervisor);
        u = repository.save(u);

        auditLogService.log(opUser, "UNQUALIFIED_DISPOSE", REF_UNQUALIFIED, u.getId(),
                "不合格品处理 " + u.getBatchNo() + " 数量 " + u.getQuantity().toPlainString()
                        + "，方式：" + method + "，监督人：" + supervisor);
        return toVO(u);
    }

    public PageResult<UnqualifiedVO> list(int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s);
        Page<UnqualifiedVO> result = repository.findAllWithProduct(pageable);
        return new PageResult<>(result.getContent(), result.getTotalElements(), p, s);
    }

    private UnqualifiedVO toVO(Unqualified u) {
        Product product = productRepository.findById(u.getProductId()).orElse(null);
        String warehouseName = u.getWarehouseId() == null ? null
                : warehouseRepository.findById(u.getWarehouseId()).map(Warehouse::getName).orElse(null);
        return new UnqualifiedVO(u.getId(), u.getProductId(), u.getBatchNo(), u.getQuantity(),
                u.getReason(), u.getStatus(), u.getCreatedBy(), u.getCreatedAt(),
                product == null ? null : product.getGenericName(),
                product == null ? null : product.getTradeName(),
                product == null ? null : product.getSpec(),
                u.getWarehouseId(), warehouseName,
                u.getDisposeMethod(), u.getDisposedAt(), u.getDisposedBy());
    }
}
