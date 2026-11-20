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
import com.pharma.system.service.AuditLogService;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** 审计日志服务：收款核销留痕（与业务同事务，失败一起回滚）。 */
    private final AuditLogService auditLogService;

    public ReceiptService(ReceiptRepository receiptRepository,
                          ReceiptItemRepository receiptItemRepository,
                          ArInvoiceRepository arInvoiceRepository,
                          CustomerRepository customerRepository,
                          ArApService arApService,
                          InventoryLedgerService ledger,
                          AuditLogService auditLogService) {
        this.receiptRepository = receiptRepository;
        this.receiptItemRepository = receiptItemRepository;
        this.arInvoiceRepository = arInvoiceRepository;
        this.customerRepository = customerRepository;
        this.arApService = arApService;
        this.ledger = ledger;
        this.auditLogService = auditLogService;
    }

    /**
     * 创建收款单并对目标应收单核销：同一应收单在一张收款单内只允许出现一次；
     * 按应收单 ID 升序加行级悲观锁后重读未结金额校验，防止并发重复核销与超收；
     * 成功后写审计日志留痕（收款单号、客户、金额、逐行核销明细）。
     *
     * @param req 收款创建请求（客户、核销明细、可选收款金额与备注）
     * @return 收款单视图，含逐行核销明细结果
     * @throws BusinessException 客户/应收单不存在、明细重复、应收单已作废或核销金额超过未结金额时抛出 400
     */
    @Transactional
    public ReceiptVO create(ReceiptCreateRequest req) {
        // 1. 基础校验：收款客户与核销明细不能为空，客户必须存在
        if (req == null || req.customerId() == null) {
            throw BusinessException.badRequest("收款客户不能为空");
        }
        customerRepository.findById(req.customerId())
                .orElseThrow(() -> BusinessException.badRequest("客户不存在: " + req.customerId()));
        List<ReceiptItemRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("收款核销明细不能为空");
        }

        // 2. 明细校验 + 去重：同一应收单重复出现直接拒绝，避免逐行叠加绕过未结金额校验
        Set<Long> arInvoiceIdSet = new LinkedHashSet<>();
        for (ReceiptItemRequest item : items) {
            if (item.arInvoiceId() == null) {
                throw BusinessException.badRequest("应收单不能为空");
            }
            if (item.amount() == null || item.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("核销金额必须大于0");
            }
            if (!arInvoiceIdSet.add(item.arInvoiceId())) {
                throw BusinessException.badRequest("同一应收单不可重复核销: " + item.arInvoiceId());
            }
        }

        // 3. 按应收单 ID 升序加悲观写锁：固定加锁顺序避免并发死锁，锁后读取最新已核销金额
        List<Long> arInvoiceIdList = new ArrayList<>(arInvoiceIdSet);
        arInvoiceIdList.sort(Comparator.naturalOrder());
        Map<Long, ArInvoice> arInvoiceMap = new HashMap<>();
        for (Long arInvoiceId : arInvoiceIdList) {
            ArInvoice lockedInvoice = arInvoiceRepository.findByIdForUpdate(arInvoiceId)
                    .orElseThrow(() -> BusinessException.badRequest("应收单不存在: " + arInvoiceId));
            arInvoiceMap.put(arInvoiceId, lockedInvoice);
        }

        // 4. 锁后逐行校验：客户归属、作废状态、未结金额均以锁定的最新数据为准
        BigDecimal total = BigDecimal.ZERO;
        for (ReceiptItemRequest item : items) {
            ArInvoice arInvoice = arInvoiceMap.get(item.arInvoiceId());
            if (!req.customerId().equals(arInvoice.getCustomerId())) {
                throw BusinessException.badRequest("应收单不属于该客户: " + arInvoice.getInvoiceNo());
            }
            if (ArApService.STATUS_VOID.equals(arInvoice.getStatus())) {
                throw BusinessException.badRequest("应收单已作废，不可核销: " + arInvoice.getInvoiceNo());
            }
            BigDecimal outstanding = arInvoice.getInvoiceAmount().subtract(arInvoice.getSettledAmount());
            if (item.amount().compareTo(outstanding) > 0) {
                throw BusinessException.badRequest("核销金额超过应收未结金额: " + arInvoice.getInvoiceNo()
                        + " 未结 " + outstanding.toPlainString() + " < 核销 " + item.amount().toPlainString());
            }
            total = total.add(item.amount());
        }

        // 5. 收款金额与核销明细合计一致性校验（未传 amount 时以明细合计为准）
        BigDecimal amount = req.amount() == null ? total : req.amount();
        if (amount.compareTo(total) != 0) {
            throw BusinessException.badRequest("收款金额与核销明细合计不一致: 收款 " + amount.toPlainString()
                    + "，明细合计 " + total.toPlainString());
        }

        // 6. 落收款单头（金额按分保留两位小数）
        Receipt receipt = new Receipt();
        receipt.setReceiptNo(ledger.genNo("SK"));
        receipt.setCustomerId(req.customerId());
        receipt.setAmount(total.setScale(2, RoundingMode.HALF_UP));
        receipt.setStatus("ACTIVE");
        receipt.setRemark(req.remark());
        receipt.setCreatedBy(ledger.currentUser());
        receipt.setReceiptDate(LocalDate.now());
        receipt = receiptRepository.save(receipt);

        // 7. 逐行写核销明细并累加应收已核销金额、推进核销状态
        List<ReceiptItemVO> itemVOs = new ArrayList<>();
        for (ReceiptItemRequest item : items) {
            ArInvoice arInvoice = arInvoiceMap.get(item.arInvoiceId());

            ReceiptItem receiptItem = new ReceiptItem();
            receiptItem.setReceiptId(receipt.getId());
            receiptItem.setArInvoiceId(arInvoice.getId());
            receiptItem.setAmount(item.amount().setScale(2, RoundingMode.HALF_UP));
            receiptItem = receiptItemRepository.save(receiptItem);

            BigDecimal settledAmount = arInvoice.getSettledAmount().add(item.amount());
            arInvoice.setSettledAmount(settledAmount);
            arInvoice.setStatus(arApService.settleStatus(arInvoice.getInvoiceAmount(), settledAmount));
            arInvoiceRepository.save(arInvoice);

            itemVOs.add(new ReceiptItemVO(receiptItem.getId(), arInvoice.getId(), arInvoice.getInvoiceNo(),
                    receiptItem.getAmount()));
        }

        // 8. 审计留痕：记录收款单号、客户、金额与逐行核销明细（与业务同事务，失败一起回滚）
        String customerName = customerName(receipt.getCustomerId());
        auditLogService.log(receipt.getCreatedBy(), "CREATE_RECEIPT", "RECEIPT", receipt.getId(),
                "创建收款单 " + receipt.getReceiptNo() + "，客户 " + customerName + "（" + receipt.getCustomerId()
                        + "），金额 " + receipt.getAmount().toPlainString()
                        + "，核销明细 " + auditItemDetail(items, arInvoiceMap),
                null, AuditLogService.json("receiptNo", receipt.getReceiptNo(),
                        "customerId", receipt.getCustomerId(), "customerName", customerName,
                        "amount", receipt.getAmount(), "itemCount", items.size()));

        // 9. 组装返回视图
        return new ReceiptVO(receipt.getId(), receipt.getReceiptNo(), receipt.getCustomerId(),
                customerName, receipt.getAmount(), receipt.getStatus(),
                receipt.getRemark(), receipt.getCreatedBy(), receipt.getCreatedAt(), itemVOs);
    }

    /**
     * 拼接收款核销明细审计文本：每行「应收发票号:核销金额」，超长截断以适配 audit_log.detail(1000)。
     *
     * @param items        收款核销明细请求
     * @param arInvoiceMap 已加锁的应收单，key 为应收单 ID
     * @return 形如「AR20261120000001:120.00, AR20261120000002:80.00」的审计文本
     */
    private String auditItemDetail(List<ReceiptItemRequest> items, Map<Long, ArInvoice> arInvoiceMap) {
        String detail = items.stream()
                .map(item -> arInvoiceMap.get(item.arInvoiceId()).getInvoiceNo() + ":"
                        + item.amount().setScale(2, RoundingMode.HALF_UP).toPlainString())
                .collect(Collectors.joining(", "));
        return detail.length() <= 800 ? detail : detail.substring(0, 800) + "...";
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
