package com.pharma.base.service;

import com.pharma.base.entity.Product;
import com.pharma.base.repository.ProductRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.EnumValidator;
import com.pharma.common.PageResult;
import com.pharma.common.SecurityUtils;
import com.pharma.common.StatusFilter;
import com.pharma.common.enums.EnableStatus;
import com.pharma.common.enums.FirstAuditStatus;
import com.pharma.common.enums.RxCategory;
import com.pharma.common.enums.StorageCond;
import com.pharma.inventory.repository.StockRepository;
import com.pharma.purchase.repository.PurchaseOrderItemRepository;
import com.pharma.purchase.repository.PurchaseReceiveItemRepository;
import com.pharma.sales.repository.SalesDeliveryItemRepository;
import com.pharma.sales.repository.SalesOrderItemRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository repository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final PurchaseReceiveItemRepository purchaseReceiveItemRepository;
    private final SalesDeliveryItemRepository salesDeliveryItemRepository;
    private final StockRepository stockRepository;

    public ProductService(ProductRepository repository,
                          PurchaseOrderItemRepository purchaseOrderItemRepository,
                          SalesOrderItemRepository salesOrderItemRepository,
                          PurchaseReceiveItemRepository purchaseReceiveItemRepository,
                          SalesDeliveryItemRepository salesDeliveryItemRepository,
                          StockRepository stockRepository) {
        this.repository = repository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.purchaseReceiveItemRepository = purchaseReceiveItemRepository;
        this.salesDeliveryItemRepository = salesDeliveryItemRepository;
        this.stockRepository = stockRepository;
    }

    public PageResult<Product> list(String keyword, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<Product> result = repository.search(keyword, StatusFilter.normalize(status), pageable);
        return new PageResult<>(result.getContent(), result.getTotalElements(), p, s);
    }

    /** 详情只返回启用（未软删）商品 */
    public Product get(Long id) {
        Product product = repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("商品不存在: " + id));
        if ("INACTIVE".equals(product.getStatus())) {
            throw BusinessException.notFound("商品不存在: " + id);
        }
        return product;
    }

    @Transactional
    public Product create(Product product) {
        if (product.getCode() == null || product.getCode().isBlank()) {
            throw BusinessException.badRequest("商品编码不能为空");
        }
        if (product.getGenericName() == null || product.getGenericName().isBlank()) {
            throw BusinessException.badRequest("通用名不能为空");
        }
        if (repository.existsByCode(product.getCode())) {
            throw BusinessException.badRequest("商品编码已存在: " + product.getCode());
        }
        validateEnums(product);
        product.setId(null);
        // P2-10 首营本人回避：记录资料创建人
        product.setCreatedBy(SecurityUtils.currentUser());
        return repository.save(product);
    }

    @Transactional
    public Product update(Long id, Product body) {
        Product existing = requireExisting(id);
        if (body.getCode() != null && !body.getCode().isBlank()
                && !body.getCode().equals(existing.getCode())
                && repository.existsByCode(body.getCode())) {
            throw BusinessException.badRequest("商品编码已存在: " + body.getCode());
        }
        if (body.getCode() != null) {
            existing.setCode(body.getCode());
        }
        if (body.getGenericName() != null) {
            existing.setGenericName(body.getGenericName());
        }
        if (body.getTradeName() != null) {
            existing.setTradeName(body.getTradeName());
        }
        if (body.getApprovalNo() != null) {
            existing.setApprovalNo(body.getApprovalNo());
        }
        if (body.getDosageForm() != null) {
            existing.setDosageForm(body.getDosageForm());
        }
        if (body.getSpec() != null) {
            existing.setSpec(body.getSpec());
        }
        if (body.getManufacturer() != null) {
            existing.setManufacturer(body.getManufacturer());
        }
        if (body.getRxCategory() != null) {
            existing.setRxCategory(body.getRxCategory());
        }
        if (body.getStorageCond() != null) {
            existing.setStorageCond(body.getStorageCond());
        }
        if (body.getNearExpiryDays() != null) {
            existing.setNearExpiryDays(body.getNearExpiryDays());
        }
        // 首营状态只能经 FirstAuditService 流转，普通编辑一律忽略（P0-2 字段越权）
        if (body.getStatus() != null) {
            existing.setStatus(body.getStatus());
        }
        validateEnums(existing);
        return repository.save(existing);
    }

    /**
     * 删除 = 软删除（P0-4）：仅置 status=INACTIVE，不物理删除，保留历史单据/追溯可查。
     * 被订单、出入库或库存引用时拒绝，避免主数据消失导致业务断链。
     */
    @Transactional
    public void delete(Long id) {
        Product product = repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("商品不存在: " + id));
        if ("INACTIVE".equals(product.getStatus())) {
            return;
        }
        boolean referenced = purchaseOrderItemRepository.existsByProductId(id)
                || salesOrderItemRepository.existsByProductId(id)
                || purchaseReceiveItemRepository.existsByProductId(id)
                || salesDeliveryItemRepository.existsByProductId(id)
                || stockRepository.existsByProductId(id);
        if (referenced) {
            throw BusinessException.badRequest("商品已被订单/入库/出库/库存引用，无法删除，请改为停用");
        }
        product.setStatus("INACTIVE");
        repository.save(product);
    }

    private Product requireExisting(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("商品不存在: " + id));
    }

    private void validateEnums(Product p) {
        EnumValidator.validate("rxCategory", p.getRxCategory(), RxCategory.class);
        EnumValidator.validate("storageCond", p.getStorageCond(), StorageCond.class);
        EnumValidator.validate("firstAuditStatus", p.getFirstAuditStatus(), FirstAuditStatus.class);
        EnumValidator.validate("status", p.getStatus(), EnableStatus.class);
    }
}
