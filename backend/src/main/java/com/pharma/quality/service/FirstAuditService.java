package com.pharma.quality.service;

import com.pharma.approval.entity.ApprovalRecord;
import com.pharma.approval.repository.ApprovalRecordRepository;
import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Product;
import com.pharma.base.entity.Supplier;
import com.pharma.base.repository.CustomerRepository;
import com.pharma.base.repository.ProductRepository;
import com.pharma.base.repository.SupplierRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.inventory.service.InventoryLedgerService;
import com.pharma.quality.dto.FirstAuditPendingVO;
import com.pharma.quality.dto.FirstAuditResult;
import com.pharma.quality.dto.LicenseWarning;
import com.pharma.system.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 首营审核 + 证照预警服务（R3 / R4）。
 */
@Service
public class FirstAuditService {

    public static final String BIZ_FIRST_AUDIT = "FIRST_AUDIT";

    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final CustomerRepository customerRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final AuditLogService auditLogService;
    private final InventoryLedgerService ledger;

    public FirstAuditService(ProductRepository productRepository,
                             SupplierRepository supplierRepository,
                             CustomerRepository customerRepository,
                             ApprovalRecordRepository approvalRecordRepository,
                             AuditLogService auditLogService,
                             InventoryLedgerService ledger) {
        this.productRepository = productRepository;
        this.supplierRepository = supplierRepository;
        this.customerRepository = customerRepository;
        this.approvalRecordRepository = approvalRecordRepository;
        this.auditLogService = auditLogService;
        this.ledger = ledger;
    }

    @Transactional
    public FirstAuditResult approve(String type, Long id, String comment) {
        return audit(type, id, "APPROVED", comment);
    }

    @Transactional
    public FirstAuditResult reject(String type, Long id, String comment) {
        return audit(type, id, "REJECTED", comment);
    }

    private FirstAuditResult audit(String type, Long id, String status, String comment) {
        if (!List.of("product", "supplier", "customer").contains(type)) {
            throw BusinessException.badRequest("首营类型必须为 product / supplier / customer");
        }
        String auditor = ledger.currentUser();
        requireNotSelfSubmitted(type, id, auditor);
        String beforeStatus;
        switch (type) {
            case "product" -> {
                Product p = productRepository.findById(id)
                        .orElseThrow(() -> BusinessException.notFound("商品不存在: " + id));
                beforeStatus = p.getFirstAuditStatus();
                p.setFirstAuditStatus(status);
                productRepository.save(p);
            }
            case "supplier" -> {
                Supplier s = supplierRepository.findById(id)
                        .orElseThrow(() -> BusinessException.notFound("供应商不存在: " + id));
                beforeStatus = s.getFirstAuditStatus();
                s.setFirstAuditStatus(status);
                supplierRepository.save(s);
            }
            case "customer" -> {
                Customer c = customerRepository.findById(id)
                        .orElseThrow(() -> BusinessException.notFound("客户不存在: " + id));
                beforeStatus = c.getFirstAuditStatus();
                c.setFirstAuditStatus(status);
                customerRepository.save(c);
            }
            default -> throw new IllegalStateException("unreachable");
        }
        writeApprovalRecord(type, id, status, comment);
        auditLogService.log(auditor, "FIRST_AUDIT_" + status,
                type.toUpperCase(), id, "首营审核 " + type + ":" + id + " -> " + status,
                AuditLogService.json("type", type, "id", id, "firstAuditStatus", beforeStatus),
                AuditLogService.json("type", type, "id", id, "firstAuditStatus", status,
                        "comment", comment));
        return new FirstAuditResult(type, id, status);
    }

    /**
     * P2-10 首营本人回避：审核人（通过/驳回）不得等于资料创建人 → 403。
     * created_by 为空的历史数据不做阻断。
     */
    private void requireNotSelfSubmitted(String type, Long id, String auditor) {
        String createdBy = switch (type) {
            case "product" -> productRepository.findById(id).map(Product::getCreatedBy).orElse(null);
            case "supplier" -> supplierRepository.findById(id).map(Supplier::getCreatedBy).orElse(null);
            case "customer" -> customerRepository.findById(id).map(Customer::getCreatedBy).orElse(null);
            default -> null;
        };
        if (createdBy != null && createdBy.equals(auditor)) {
            throw BusinessException.forbidden("不能审核本人提交的首营资料");
        }
    }

    private void writeApprovalRecord(String type, Long id, String status, String comment) {
        ApprovalRecord r = new ApprovalRecord();
        r.setBizType(BIZ_FIRST_AUDIT);
        r.setBizId(id);
        r.setNode(type);
        r.setApprover(ledger.currentUser());
        r.setStatus(status);
        r.setComment(comment);
        approvalRecordRepository.save(r);
    }

    public PageResult<FirstAuditPendingVO> pending(String type, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));

        if (type == null || type.isBlank()) {
            type = "product";
        }
        List<FirstAuditPendingVO> records = new ArrayList<>();
        long total = 0;
        switch (type) {
            case "product" -> {
                Page<Product> result = productRepository.findByFirstAuditStatusAndStatus("PENDING", "ACTIVE", pageable);
                records = result.getContent().stream()
                        .map(x -> new FirstAuditPendingVO("product", x.getId(), x.getGenericName(), x.getCode(),
                                x.getFirstAuditStatus(), null, x.getCreatedAt()))
                        .toList();
                total = result.getTotalElements();
            }
            case "supplier" -> {
                Page<Supplier> result = supplierRepository.findByFirstAuditStatusAndStatus("PENDING", "ACTIVE", pageable);
                records = result.getContent().stream()
                        .map(x -> new FirstAuditPendingVO("supplier", x.getId(), x.getName(), x.getCreditCode(),
                                x.getFirstAuditStatus(), x.getLicenseExpire(), null))
                        .toList();
                total = result.getTotalElements();
            }
            case "customer" -> {
                Page<Customer> result = customerRepository.findByFirstAuditStatusAndStatus("PENDING", "ACTIVE", pageable);
                records = result.getContent().stream()
                        .map(x -> new FirstAuditPendingVO("customer", x.getId(), x.getName(), x.getLicenseNo(),
                                x.getFirstAuditStatus(), x.getLicenseExpire(), null))
                        .toList();
                total = result.getTotalElements();
            }
            default -> throw BusinessException.badRequest("首营类型必须为 product / supplier / customer");
        }
        return new PageResult<>(records, total, p, s);
    }

    /**
     * R4 证照预警：返回 license_expire 距今 <= days 天（默认 90）的供应商/客户，
     * 按到期日升序，含已过期（daysLeft 为负）。
     */
    public List<LicenseWarning> expiringLicenses(int days) {
        int d = days <= 0 ? 90 : days;
        LocalDate today = LocalDate.now();
        LocalDate deadline = today.plusDays(d);

        List<LicenseWarning> warnings = new ArrayList<>();
        supplierRepository.findAll().stream()
                .filter(s -> !"INACTIVE".equals(s.getStatus()))
                .filter(s -> s.getLicenseExpire() != null && !s.getLicenseExpire().isAfter(deadline))
                .forEach(s -> warnings.add(new LicenseWarning("supplier", s.getId(), s.getName(),
                        s.getLicenseNo(), s.getLicenseExpire(),
                        ChronoUnit.DAYS.between(today, s.getLicenseExpire()))));
        customerRepository.findAll().stream()
                .filter(c -> !"INACTIVE".equals(c.getStatus()))
                .filter(c -> c.getLicenseExpire() != null && !c.getLicenseExpire().isAfter(deadline))
                .forEach(c -> warnings.add(new LicenseWarning("customer", c.getId(), c.getName(),
                        c.getLicenseNo(), c.getLicenseExpire(),
                        ChronoUnit.DAYS.between(today, c.getLicenseExpire()))));

        warnings.sort((a, b) -> a.licenseExpire().compareTo(b.licenseExpire()));
        return warnings;
    }
}
