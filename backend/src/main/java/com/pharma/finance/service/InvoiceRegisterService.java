package com.pharma.finance.service;

import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.finance.dto.InvoiceRegisterCreateRequest;
import com.pharma.finance.dto.InvoiceRegisterVO;
import com.pharma.finance.entity.InvoiceRegister;
import com.pharma.finance.repository.InvoiceRegisterRepository;
import com.pharma.inventory.service.InventoryLedgerService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 发票登记：收票/开票留痕，可关联业务单据（biz_type/biz_id 可空）。
 */
@Service
public class InvoiceRegisterService {

    private final InvoiceRegisterRepository repository;
    private final InventoryLedgerService ledger;

    public InvoiceRegisterService(InvoiceRegisterRepository repository, InventoryLedgerService ledger) {
        this.repository = repository;
        this.ledger = ledger;
    }

    @Transactional
    public InvoiceRegisterVO create(InvoiceRegisterCreateRequest req) {
        if (req == null || req.invoiceNo() == null || req.invoiceNo().isBlank()) {
            throw BusinessException.badRequest("发票号不能为空");
        }
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("发票金额必须大于0");
        }
        InvoiceRegister entity = new InvoiceRegister();
        entity.setInvoiceNo(req.invoiceNo().trim());
        entity.setBizType(req.bizType());
        entity.setBizId(req.bizId());
        entity.setAmount(req.amount());
        entity.setRemark(req.remark());
        entity.setStatus("ACTIVE");
        entity.setCreatedBy(ledger.currentUser());
        entity = repository.save(entity);
        return toVO(entity);
    }

    public PageResult<InvoiceRegisterVO> list(String keyword, String bizType, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<InvoiceRegister> result = repository.search(
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                bizType == null || bizType.isBlank() ? null : bizType.trim(), pageable);
        List<InvoiceRegisterVO> records = result.getContent().stream().map(this::toVO).toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    private InvoiceRegisterVO toVO(InvoiceRegister e) {
        return new InvoiceRegisterVO(e.getId(), e.getInvoiceNo(), e.getBizType(), e.getBizId(),
                e.getAmount(), e.getStatus(), e.getRemark(), e.getCreatedBy(), e.getCreatedAt());
    }
}
