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
import com.pharma.system.service.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** 审计日志服务：付款核销留痕（与业务同事务，失败一起回滚）。 */
    private final AuditLogService auditLogService;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentItemRepository paymentItemRepository,
                          ApInvoiceRepository apInvoiceRepository,
                          SupplierRepository supplierRepository,
                          ArApService arApService,
                          InventoryLedgerService ledger,
                          AuditLogService auditLogService) {
        this.paymentRepository = paymentRepository;
        this.paymentItemRepository = paymentItemRepository;
        this.apInvoiceRepository = apInvoiceRepository;
        this.supplierRepository = supplierRepository;
        this.arApService = arApService;
        this.ledger = ledger;
        this.auditLogService = auditLogService;
    }

    /**
     * 创建付款单并对目标应付单核销：同一应付单在一张付款单内只允许出现一次；
     * 按应付单 ID 升序加行级悲观锁后重读未结金额校验，防止并发重复核销与超付；
     * 成功后写审计日志留痕（付款单号、供应商、金额、逐行核销明细）。
     *
     * @param req 付款创建请求（供应商、核销明细、可选付款金额与备注）
     * @return 付款单视图，含逐行核销明细结果
     * @throws BusinessException 供应商/应付单不存在、明细重复、应付单已作废或核销金额超过未结金额时抛出 400
     */
    @Transactional
    public PaymentVO create(PaymentCreateRequest req) {
        // 1. 基础校验：付款供应商与核销明细不能为空，供应商必须存在
        if (req == null || req.supplierId() == null) {
            throw BusinessException.badRequest("付款供应商不能为空");
        }
        supplierRepository.findById(req.supplierId())
                .orElseThrow(() -> BusinessException.badRequest("供应商不存在: " + req.supplierId()));
        List<PaymentItemRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("付款核销明细不能为空");
        }

        // 2. 明细校验 + 去重：同一应付单重复出现直接拒绝，避免逐行叠加绕过未结金额校验
        Set<Long> apInvoiceIdSet = new LinkedHashSet<>();
        for (PaymentItemRequest item : items) {
            if (item.apInvoiceId() == null) {
                throw BusinessException.badRequest("应付单不能为空");
            }
            if (item.amount() == null || item.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("核销金额必须大于0");
            }
            if (!apInvoiceIdSet.add(item.apInvoiceId())) {
                throw BusinessException.badRequest("同一应付单不可重复核销: " + item.apInvoiceId());
            }
        }

        // 3. 按应付单 ID 升序加悲观写锁：固定加锁顺序避免并发死锁，锁后读取最新已核销金额
        List<Long> apInvoiceIdList = new ArrayList<>(apInvoiceIdSet);
        apInvoiceIdList.sort(Comparator.naturalOrder());
        Map<Long, ApInvoice> apInvoiceMap = new HashMap<>();
        for (Long apInvoiceId : apInvoiceIdList) {
            ApInvoice lockedInvoice = apInvoiceRepository.findByIdForUpdate(apInvoiceId)
                    .orElseThrow(() -> BusinessException.badRequest("应付单不存在: " + apInvoiceId));
            apInvoiceMap.put(apInvoiceId, lockedInvoice);
        }

        // 4. 锁后逐行校验：供应商归属、作废状态、未结金额均以锁定的最新数据为准
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentItemRequest item : items) {
            ApInvoice apInvoice = apInvoiceMap.get(item.apInvoiceId());
            if (!req.supplierId().equals(apInvoice.getSupplierId())) {
                throw BusinessException.badRequest("应付单不属于该供应商: " + apInvoice.getInvoiceNo());
            }
            if (ArApService.STATUS_VOID.equals(apInvoice.getStatus())) {
                throw BusinessException.badRequest("应付单已作废，不可核销: " + apInvoice.getInvoiceNo());
            }
            BigDecimal outstanding = apInvoice.getInvoiceAmount().subtract(apInvoice.getSettledAmount());
            if (item.amount().compareTo(outstanding) > 0) {
                throw BusinessException.badRequest("核销金额超过应付未结金额: " + apInvoice.getInvoiceNo()
                        + " 未结 " + outstanding.toPlainString() + " < 核销 " + item.amount().toPlainString());
            }
            total = total.add(item.amount());
        }

        // 5. 付款金额与核销明细合计一致性校验（未传 amount 时以明细合计为准）
        BigDecimal amount = req.amount() == null ? total : req.amount();
        if (amount.compareTo(total) != 0) {
            throw BusinessException.badRequest("付款金额与核销明细合计不一致: 付款 " + amount.toPlainString()
                    + "，明细合计 " + total.toPlainString());
        }

        // 6. 落付款单头（金额按分保留两位小数）
        Payment payment = new Payment();
        payment.setPaymentNo(ledger.genNo("FK"));
        payment.setSupplierId(req.supplierId());
        payment.setAmount(total.setScale(2, RoundingMode.HALF_UP));
        payment.setStatus("ACTIVE");
        payment.setRemark(req.remark());
        payment.setCreatedBy(ledger.currentUser());
        payment = paymentRepository.save(payment);

        // 7. 逐行写核销明细并累加应付已核销金额、推进核销状态
        List<PaymentItemVO> itemVOs = new ArrayList<>();
        for (PaymentItemRequest item : items) {
            ApInvoice apInvoice = apInvoiceMap.get(item.apInvoiceId());

            PaymentItem paymentItem = new PaymentItem();
            paymentItem.setPaymentId(payment.getId());
            paymentItem.setApInvoiceId(apInvoice.getId());
            paymentItem.setAmount(item.amount().setScale(2, RoundingMode.HALF_UP));
            paymentItem = paymentItemRepository.save(paymentItem);

            BigDecimal settledAmount = apInvoice.getSettledAmount().add(item.amount());
            apInvoice.setSettledAmount(settledAmount);
            apInvoice.setStatus(arApService.settleStatus(apInvoice.getInvoiceAmount(), settledAmount));
            apInvoiceRepository.save(apInvoice);

            itemVOs.add(new PaymentItemVO(paymentItem.getId(), apInvoice.getId(), apInvoice.getInvoiceNo(),
                    paymentItem.getAmount()));
        }

        // 8. 审计留痕：记录付款单号、供应商、金额与逐行核销明细（与业务同事务，失败一起回滚）
        String supplierName = supplierName(payment.getSupplierId());
        auditLogService.log(payment.getCreatedBy(), "CREATE_PAYMENT", "PAYMENT", payment.getId(),
                "创建付款单 " + payment.getPaymentNo() + "，供应商 " + supplierName + "（" + payment.getSupplierId()
                        + "），金额 " + payment.getAmount().toPlainString()
                        + "，核销明细 " + auditItemDetail(items, apInvoiceMap),
                null, AuditLogService.json("paymentNo", payment.getPaymentNo(),
                        "supplierId", payment.getSupplierId(), "supplierName", supplierName,
                        "amount", payment.getAmount(), "itemCount", items.size()));

        // 9. 组装返回视图
        return new PaymentVO(payment.getId(), payment.getPaymentNo(), payment.getSupplierId(),
                supplierName, payment.getAmount(), payment.getStatus(),
                payment.getRemark(), payment.getCreatedBy(), payment.getCreatedAt(), itemVOs);
    }

    /**
     * 拼接付款核销明细审计文本：每行「应付发票号:核销金额」，超长截断以适配 audit_log.detail(1000)。
     *
     * @param items        付款核销明细请求
     * @param apInvoiceMap 已加锁的应付单，key 为应付单 ID
     * @return 形如「AP20261120000001:30.00, AP20261120000002:20.00」的审计文本
     */
    private String auditItemDetail(List<PaymentItemRequest> items, Map<Long, ApInvoice> apInvoiceMap) {
        String detail = items.stream()
                .map(item -> apInvoiceMap.get(item.apInvoiceId()).getInvoiceNo() + ":"
                        + item.amount().setScale(2, RoundingMode.HALF_UP).toPlainString())
                .collect(Collectors.joining(", "));
        return detail.length() <= 800 ? detail : detail.substring(0, 800) + "...";
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
