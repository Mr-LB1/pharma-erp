package com.pharma.base.service;

import com.pharma.base.entity.Supplier;
import com.pharma.base.repository.SupplierRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.EnumValidator;
import com.pharma.common.PageResult;
import com.pharma.common.SecurityUtils;
import com.pharma.common.StatusFilter;
import com.pharma.common.enums.EnableStatus;
import com.pharma.common.enums.FirstAuditStatus;
import com.pharma.common.enums.SettleType;
import com.pharma.purchase.repository.PurchaseOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierService {

    private final SupplierRepository repository;
    private final PurchaseOrderRepository purchaseOrderRepository;

    public SupplierService(SupplierRepository repository, PurchaseOrderRepository purchaseOrderRepository) {
        this.repository = repository;
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    public PageResult<Supplier> list(String keyword, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<Supplier> result = repository.search(keyword, StatusFilter.normalize(status), pageable);
        return new PageResult<>(result.getContent(), result.getTotalElements(), p, s);
    }

    /** 详情只返回启用（未软删）供应商 */
    public Supplier get(Long id) {
        Supplier supplier = requireExisting(id);
        if ("INACTIVE".equals(supplier.getStatus())) {
            throw BusinessException.notFound("供应商不存在: " + id);
        }
        return supplier;
    }

    /**
     * 新增供应商。
     * <p>首营状态一律由系统置为 PENDING（忽略客户端传值），后续只能经 FirstAuditService 审核流转，
     * 避免新增资料直接以 APPROVED 落库绕过首营审核（P0-4）。</p>
     *
     * @param supplier 待新增供应商，其 firstAuditStatus 传值不生效
     * @return 保存后的供应商
     */
    @Transactional
    public Supplier create(Supplier supplier) {
        if (supplier.getName() == null || supplier.getName().isBlank()) {
            throw BusinessException.badRequest("供应商名称不能为空");
        }
        validateEnums(supplier);
        // P0-4 首营越权：新增资料强制 PENDING，覆盖客户端传入的 firstAuditStatus
        supplier.setFirstAuditStatus(FirstAuditStatus.PENDING.name());
        supplier.setId(null);
        // P2-10 首营本人回避：记录资料创建人
        supplier.setCreatedBy(SecurityUtils.currentUser());
        return repository.save(supplier);
    }

    @Transactional
    public Supplier update(Long id, Supplier body) {
        Supplier existing = requireExisting(id);
        if (body.getName() != null) {
            existing.setName(body.getName());
        }
        if (body.getCreditCode() != null) {
            existing.setCreditCode(body.getCreditCode());
        }
        if (body.getLicenseNo() != null) {
            existing.setLicenseNo(body.getLicenseNo());
        }
        if (body.getLicenseExpire() != null) {
            existing.setLicenseExpire(body.getLicenseExpire());
        }
        if (body.getGspCert() != null) {
            existing.setGspCert(body.getGspCert());
        }
        // 首营状态只能经 FirstAuditService 流转，普通编辑一律忽略（P0-2 字段越权）
        if (body.getSettleType() != null) {
            existing.setSettleType(body.getSettleType());
        }
        if (body.getStatus() != null) {
            existing.setStatus(body.getStatus());
        }
        validateEnums(existing);
        return repository.save(existing);
    }

    /** 删除 = 软删除（P0-4）：被采购订单引用时拒绝，仅置 status=INACTIVE。 */
    @Transactional
    public void delete(Long id) {
        Supplier supplier = requireExisting(id);
        if ("INACTIVE".equals(supplier.getStatus())) {
            return;
        }
        if (purchaseOrderRepository.existsBySupplierId(id)) {
            throw BusinessException.badRequest("供应商已被采购订单引用，无法删除，请改为停用");
        }
        supplier.setStatus("INACTIVE");
        repository.save(supplier);
    }

    private Supplier requireExisting(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("供应商不存在: " + id));
    }

    private void validateEnums(Supplier s) {
        EnumValidator.validate("firstAuditStatus", s.getFirstAuditStatus(), FirstAuditStatus.class);
        EnumValidator.validate("settleType", s.getSettleType(), SettleType.class);
        EnumValidator.validate("status", s.getStatus(), EnableStatus.class);
    }
}
