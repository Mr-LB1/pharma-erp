package com.pharma.finance.service;

import com.pharma.base.entity.Customer;
import com.pharma.base.repository.CustomerRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.finance.dto.ReceiptCreateRequest;
import com.pharma.finance.dto.ReceiptItemRequest;
import com.pharma.finance.dto.ReceiptItemVO;
import com.pharma.finance.dto.ReceiptVO;
import com.pharma.finance.entity.ArInvoice;
import com.pharma.finance.entity.Receipt;
import com.pharma.finance.entity.ReceiptItem;
import com.pharma.finance.repository.ArInvoiceRepository;
import com.pharma.finance.repository.ReceiptItemRepository;
import com.pharma.finance.repository.ReceiptRepository;
import com.pharma.inventory.service.InventoryLedgerService;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 收款核销：创建收款单 + 按应收单核销，累计 settled_amount 并推进
 * UNSETTLED → PARTIAL → SETTLED；禁止超收（核销额不得超过未结金额）。
 */
@Service
public class ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final ArInvoiceRepository arInvoiceRepository;
    private final CustomerRepository customerRepository;
    private final ArApService arApService;
    private final InventoryLedgerService ledger;

    public ReceiptService(ReceiptRepository receiptRepository,
                          ReceiptItemRepository receiptItemRepository,
                          ArInvoiceRepository arInvoiceRepository,
                          CustomerRepository customerRepository,
                          ArApService arApService,
                          InventoryLedgerService ledger) {
        this.receiptRepository = receiptRepository;
        this.receiptItemRepository = receiptItemRepository;
        this.arInvoiceRepository = arInvoiceRepository;
        this.customerRepository = customerRepository;
        this.arApService = arApService;
        this.ledger = ledger;
    }

    @Transactional
    public ReceiptVO create(ReceiptCreateRequest req) {
        if (req == null || req.customerId() == null) {
            throw BusinessException.badRequest("收款客户不能为空");
        }
        customerRepository.findById(req.customerId())
                .orElseThrow(() -> BusinessException.badRequest("客户不存在: " + req.customerId()));
        List<ReceiptItemRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("收款核销明细不能为空");
        }

        BigDecimal total = BigDecimal.ZERO;
        List<ArInvoice> invoices = new ArrayList<>();
        for (ReceiptItemRequest it : items) {
            if (it.arInvoiceId() == null) {
                throw BusinessException.badRequest("应收单不能为空");
            }
            if (it.amount() == null || it.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("核销金额必须大于0");
            }
            ArInvoice ar = arInvoiceRepository.findById(it.arInvoiceId())
                    .orElseThrow(() -> BusinessException.badRequest("应收单不存在: " + it.arInvoiceId()));
            if (!req.customerId().equals(ar.getCustomerId())) {
                throw BusinessException.badRequest("应收单不属于该客户: " + ar.getInvoiceNo());
            }
            if (ArApService.STATUS_VOID.equals(ar.getStatus())) {
                throw BusinessException.badRequest("应收单已作废，不可核销: " + ar.getInvoiceNo());
            }
            BigDecimal outstanding = ar.getInvoiceAmount().subtract(ar.getSettledAmount());
            if (it.amount().compareTo(outstanding) > 0) {
                throw BusinessException.badRequest("核销金额超过应收未结金额: " + ar.getInvoiceNo()
                        + " 未结 " + outstanding.toPlainString() + " < 核销 " + it.amount().toPlainString());
            }
            total = total.add(it.amount());
            invoices.add(ar);
        }

        BigDecimal amount = req.amount() == null ? total : req.amount();
        if (amount.compareTo(total) != 0) {
            throw BusinessException.badRequest("收款金额与核销明细合计不一致: 收款 " + amount.toPlainString()
                    + "，明细合计 " + total.toPlainString());
        }

        Receipt receipt = new Receipt();
        receipt.setReceiptNo(ledger.genNo("SK"));
        receipt.setCustomerId(req.customerId());
        receipt.setAmount(total.setScale(2, RoundingMode.HALF_UP));
        receipt.setStatus("ACTIVE");
        receipt.setRemark(req.remark());
        receipt.setCreatedBy(ledger.currentUser());
        receipt.setReceiptDate(LocalDate.now());
        receipt = receiptRepository.save(receipt);

        List<ReceiptItemVO> itemVOs = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ReceiptItemRequest it = items.get(i);
            ArInvoice ar = invoices.get(i);

            ReceiptItem ri = new ReceiptItem();
            ri.setReceiptId(receipt.getId());
            ri.setArInvoiceId(ar.getId());
            ri.setAmount(it.amount().setScale(2, RoundingMode.HALF_UP));
            ri = receiptItemRepository.save(ri);

            BigDecimal settled = ar.getSettledAmount().add(it.amount());
            ar.setSettledAmount(settled);
            ar.setStatus(arApService.settleStatus(ar.getInvoiceAmount(), settled));
            arInvoiceRepository.save(ar);

            itemVOs.add(new ReceiptItemVO(ri.getId(), ar.getId(), ar.getInvoiceNo(), ri.getAmount()));
        }

        return new ReceiptVO(receipt.getId(), receipt.getReceiptNo(), receipt.getCustomerId(),
                customerName(receipt.getCustomerId()), receipt.getAmount(), receipt.getStatus(),
                receipt.getRemark(), receipt.getCreatedBy(), receipt.getCreatedAt(), itemVOs);
    }

    public PageResult<ReceiptVO> list(Long customerId, String keyword, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<Receipt> result = receiptRepository.search(customerId,
                keyword == null || keyword.isBlank() ? null : keyword.trim(), pageable);

        List<Long> ids = result.getContent().stream().map(Receipt::getId).toList();
        Map<Long, List<ReceiptItem>> itemMap = receiptItemRepository.findByReceiptIdIn(ids).stream()
                .collect(Collectors.groupingBy(ReceiptItem::getReceiptId));
        Map<Long, String> invoiceNos = arInvoiceRepository
                .findAllById(itemMap.values().stream().flatMap(List::stream)
                        .map(ReceiptItem::getArInvoiceId).toList()).stream()
                .collect(Collectors.toMap(ArInvoice::getId, ArInvoice::getInvoiceNo));

        List<ReceiptVO> records = result.getContent().stream()
                .map(r -> new ReceiptVO(r.getId(), r.getReceiptNo(), r.getCustomerId(),
                        customerName(r.getCustomerId()), r.getAmount(), r.getStatus(), r.getRemark(),
                        r.getCreatedBy(), r.getCreatedAt(),
                        itemMap.getOrDefault(r.getId(), List.of()).stream()
                                .map(i -> new ReceiptItemVO(i.getId(), i.getArInvoiceId(),
                                        invoiceNos.get(i.getArInvoiceId()), i.getAmount()))
                                .toList()))
                .toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    private String customerName(Long customerId) {
        return customerRepository.findById(customerId).map(Customer::getName).orElse(null);
    }
}
