package com.pharma.base.service;

import com.pharma.base.entity.Customer;
import com.pharma.base.repository.CustomerRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.EnumValidator;
import com.pharma.common.PageResult;
import com.pharma.common.SecurityUtils;
import com.pharma.common.StatusFilter;
import com.pharma.common.enums.CreditLevel;
import com.pharma.common.enums.EnableStatus;
import com.pharma.common.enums.FirstAuditStatus;
import com.pharma.sales.repository.SalesOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final SalesOrderRepository salesOrderRepository;

    public CustomerService(CustomerRepository repository, SalesOrderRepository salesOrderRepository) {
        this.repository = repository;
        this.salesOrderRepository = salesOrderRepository;
    }

    public PageResult<Customer> list(String keyword, String status, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<Customer> result = repository.search(keyword, StatusFilter.normalize(status), pageable);
        return new PageResult<>(result.getContent(), result.getTotalElements(), p, s);
    }

    /** 详情只返回启用（未软删）客户 */
    public Customer get(Long id) {
        Customer customer = requireExisting(id);
        if ("INACTIVE".equals(customer.getStatus())) {
            throw BusinessException.notFound("客户不存在: " + id);
        }
        return customer;
    }

    @Transactional
    public Customer create(Customer customer) {
        if (customer.getName() == null || customer.getName().isBlank()) {
            throw BusinessException.badRequest("客户名称不能为空");
        }
        validateEnums(customer);
        customer.setId(null);
        // P2-10 首营本人回避：记录资料创建人
        customer.setCreatedBy(SecurityUtils.currentUser());
        return repository.save(customer);
    }

    @Transactional
    public Customer update(Long id, Customer body) {
        Customer existing = requireExisting(id);
        if (body.getName() != null) {
            existing.setName(body.getName());
        }
        if (body.getLicenseNo() != null) {
            existing.setLicenseNo(body.getLicenseNo());
        }
        if (body.getLicenseExpire() != null) {
            existing.setLicenseExpire(body.getLicenseExpire());
        }
        if (body.getCreditLevel() != null) {
            existing.setCreditLevel(body.getCreditLevel());
        }
        if (body.getCreditLimit() != null) {
            existing.setCreditLimit(body.getCreditLimit());
        }
        // 首营状态只能经 FirstAuditService 流转，普通编辑一律忽略（P0-2 字段越权）
        if (body.getStatus() != null) {
            existing.setStatus(body.getStatus());
        }
        validateEnums(existing);
        return repository.save(existing);
    }

    /** 删除 = 软删除（P0-4）：被销售订单引用时拒绝，仅置 status=INACTIVE。 */
    @Transactional
    public void delete(Long id) {
        Customer customer = requireExisting(id);
        if ("INACTIVE".equals(customer.getStatus())) {
            return;
        }
        if (salesOrderRepository.existsByCustomerId(id)) {
            throw BusinessException.badRequest("客户已被销售订单引用，无法删除，请改为停用");
        }
        customer.setStatus("INACTIVE");
        repository.save(customer);
    }

    private Customer requireExisting(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("客户不存在: " + id));
    }

    private void validateEnums(Customer c) {
        EnumValidator.validate("creditLevel", c.getCreditLevel(), CreditLevel.class);
        EnumValidator.validate("firstAuditStatus", c.getFirstAuditStatus(), FirstAuditStatus.class);
        EnumValidator.validate("status", c.getStatus(), EnableStatus.class);
    }
}
