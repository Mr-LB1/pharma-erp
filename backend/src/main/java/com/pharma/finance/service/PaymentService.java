package com.pharma.finance.service;

import com.pharma.base.entity.Supplier;
import com.pharma.base.repository.SupplierRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.finance.dto.PaymentCreateRequest;
import com.pharma.finance.dto.PaymentItemRequest;
import com.pharma.finance.dto.PaymentItemVO;
import com.pharma.finance.dto.PaymentVO;
import com.pharma.finance.entity.ApInvoice;
import com.pharma.finance.entity.Payment;
import com.pharma.finance.entity.PaymentItem;
import com.pharma.finance.repository.ApInvoiceRepository;
import com.pharma.finance.repository.PaymentItemRepository;
import com.pharma.finance.repository.PaymentRepository;
import com.pharma.inventory.service.InventoryLedgerService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 付款核销：创建付款单 + 按应付单核销，累计 settled_amount 并推进
 * UNSETTLED → PARTIAL → SETTLED；禁止超付。
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final ApInvoiceRepository apInvoiceRepository;
    private final SupplierRepository supplierRepository;
    private final ArApService arApService;
    private final InventoryLedgerService ledger;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentItemRepository paymentItemRepository,
                          ApInvoiceRepository apInvoiceRepository,
                          SupplierRepository supplierRepository,
                          ArApService arApService,
                          InventoryLedgerService ledger) {
        this.paymentRepository = paymentRepository;
        this.paymentItemRepository = paymentItemRepository;
        this.apInvoiceRepository = apInvoiceRepository;
        this.supplierRepository = supplierRepository;
        this.arApService = arApService;
        this.ledger = ledger;
    }

    @Transactional
    public PaymentVO create(PaymentCreateRequest req) {
        if (req == null || req.supplierId() == null) {
            throw BusinessException.badRequest("付款供应商不能为空");
        }
        supplierRepository.findById(req.supplierId())
                .orElseThrow(() -> BusinessException.badRequest("供应商不存在: " + req.supplierId()));
        List<PaymentItemRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("付款核销明细不能为空");
        }

        BigDecimal total = BigDecimal.ZERO;
        List<ApInvoice> invoices = new ArrayList<>();
        for (PaymentItemRequest it : items) {
            if (it.apInvoiceId() == null) {
                throw BusinessException.badRequest("应付单不能为空");
            }
            if (it.amount() == null || it.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("核销金额必须大于0");
            }
            ApInvoice ap = apInvoiceRepository.findById(it.apInvoiceId())
                    .orElseThrow(() -> BusinessException.badRequest("应付单不存在: " + it.apInvoiceId()));
            if (!req.supplierId().equals(ap.getSupplierId())) {
                throw BusinessException.badRequest("应付单不属于该供应商: " + ap.getInvoiceNo());
            }
            if (ArApService.STATUS_VOID.equals(ap.getStatus())) {
                throw BusinessException.badRequest("应付单已作废，不可核销: " + ap.getInvoiceNo());
            }
            BigDecimal outstanding = ap.getInvoiceAmount().subtract(ap.getSettledAmount());
            if (it.amount().compareTo(outstanding) > 0) {
                throw BusinessException.badRequest("核销金额超过应付未结金额: " + ap.getInvoiceNo()
                        + " 未结 " + outstanding.toPlainString() + " < 核销 " + it.amount().toPlainString());
            }
            total = total.add(it.amount());
            invoices.add(ap);
        }

        BigDecimal amount = req.amount() == null ? total : req.amount();
        if (amount.compareTo(total) != 0) {
            throw BusinessException.badRequest("付款金额与核销明细合计不一致: 付款 " + amount.toPlainString()
                    + "，明细合计 " + total.toPlainString());
        }

        Payment payment = new Payment();
        payment.setPaymentNo(ledger.genNo("FK"));
        payment.setSupplierId(req.supplierId());
        payment.setAmount(total.setScale(2, RoundingMode.HALF_UP));
        payment.setStatus("ACTIVE");
        payment.setRemark(req.remark());
        payment.setCreatedBy(ledger.currentUser());
        payment = paymentRepository.save(payment);

        List<PaymentItemVO> itemVOs = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            PaymentItemRequest it = items.get(i);
            ApInvoice ap = invoices.get(i);

            PaymentItem pi = new PaymentItem();
            pi.setPaymentId(payment.getId());
            pi.setApInvoiceId(ap.getId());
            pi.setAmount(it.amount().setScale(2, RoundingMode.HALF_UP));
            pi = paymentItemRepository.save(pi);

            BigDecimal settled = ap.getSettledAmount().add(it.amount());
            ap.setSettledAmount(settled);
            ap.setStatus(arApService.settleStatus(ap.getInvoiceAmount(), settled));
            apInvoiceRepository.save(ap);

            itemVOs.add(new PaymentItemVO(pi.getId(), ap.getId(), ap.getInvoiceNo(), pi.getAmount()));
        }

        return new PaymentVO(payment.getId(), payment.getPaymentNo(), payment.getSupplierId(),
                supplierName(payment.getSupplierId()), payment.getAmount(), payment.getStatus(),
                payment.getRemark(), payment.getCreatedBy(), payment.getCreatedAt(), itemVOs);
    }

    public PageResult<PaymentVO> list(Long supplierId, String keyword, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<Payment> result = paymentRepository.search(supplierId,
                keyword == null || keyword.isBlank() ? null : keyword.trim(), pageable);

        List<Long> ids = result.getContent().stream().map(Payment::getId).toList();
        Map<Long, List<PaymentItem>> itemMap = paymentItemRepository.findByPaymentIdIn(ids).stream()
                .collect(Collectors.groupingBy(PaymentItem::getPaymentId));
        Map<Long, String> invoiceNos = apInvoiceRepository
                .findAllById(itemMap.values().stream().flatMap(List::stream)
                        .map(PaymentItem::getApInvoiceId).toList()).stream()
                .collect(Collectors.toMap(ApInvoice::getId, ApInvoice::getInvoiceNo));

        List<PaymentVO> records = result.getContent().stream()
                .map(pay -> new PaymentVO(pay.getId(), pay.getPaymentNo(), pay.getSupplierId(),
                        supplierName(pay.getSupplierId()), pay.getAmount(), pay.getStatus(), pay.getRemark(),
                        pay.getCreatedBy(), pay.getCreatedAt(),
                        itemMap.getOrDefault(pay.getId(), List.of()).stream()
                                .map(i -> new PaymentItemVO(i.getId(), i.getApInvoiceId(),
                                        invoiceNos.get(i.getApInvoiceId()), i.getAmount()))
                                .toList()))
                .toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    private String supplierName(Long supplierId) {
        return supplierRepository.findById(supplierId).map(Supplier::getName).orElse(null);
    }
}
