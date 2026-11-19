package com.pharma.finance.service;

import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Supplier;
import com.pharma.base.repository.CustomerRepository;
import com.pharma.base.repository.SupplierRepository;
import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.finance.dto.ApInvoiceVO;
import com.pharma.finance.dto.ArInvoiceVO;
import com.pharma.finance.entity.ApInvoice;
import com.pharma.finance.entity.ArInvoice;
import com.pharma.finance.repository.ApInvoiceRepository;
import com.pharma.finance.repository.ArInvoiceRepository;
import com.pharma.inventory.service.InventoryLedgerService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 财务应收/应付核心服务：
 * - 出库自动生成应收（AR 前缀），入库自动生成应付（AP 前缀），作废单据联动 VOID；
 * - 同一业务单据重复生成时幂等复用已有未作废单据；
 * - 客户未结应收用于销售订单提审时的信用额度拦截。
 */
@Service
public class ArApService {

    public static final String BIZ_MANUAL = "MANUAL";
    public static final String STATUS_UNSETTLED = "UNSETTLED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_SETTLED = "SETTLED";
    public static final String STATUS_VOID = "VOID";

    private final ArInvoiceRepository arRepository;
    private final ApInvoiceRepository apRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final InventoryLedgerService ledger;

    public ArApService(ArInvoiceRepository arRepository,
                       ApInvoiceRepository apRepository,
                       CustomerRepository customerRepository,
                       SupplierRepository supplierRepository,
                       InventoryLedgerService ledger) {
        this.arRepository = arRepository;
        this.apRepository = apRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
        this.ledger = ledger;
    }

    /** 生成应收：金额 = 销售金额（单价 × 数量合计）。同业务单据幂等。 */
    @Transactional
    public ArInvoice generateAr(Long customerId, String bizType, Long bizId, BigDecimal amount) {
        if (customerId == null) {
            throw BusinessException.badRequest("应收客户不能为空");
        }
        if (!BIZ_MANUAL.equals(bizType)) {
            List<ArInvoice> existing = arRepository.findByBizTypeAndBizIdAndStatusNot(bizType, bizId, STATUS_VOID);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
        }
        ArInvoice ar = new ArInvoice();
        ar.setInvoiceNo(ledger.genNo("AR"));
        ar.setCustomerId(customerId);
        ar.setBizType(bizType);
        ar.setBizId(bizId);
        ar.setInvoiceAmount(scale(amount));
        ar.setSettledAmount(BigDecimal.ZERO);
        ar.setStatus(STATUS_UNSETTLED);
        ar.setCreatedBy(ledger.currentUser());
        return arRepository.save(ar);
    }

    /** 生成应付：金额 = 采购入库金额（成本价 × 数量合计）。同业务单据幂等。 */
    @Transactional
    public ApInvoice generateAp(Long supplierId, String bizType, Long bizId, BigDecimal amount) {
        if (supplierId == null) {
            throw BusinessException.badRequest("应付供应商不能为空");
        }
        if (!BIZ_MANUAL.equals(bizType)) {
            List<ApInvoice> existing = apRepository.findByBizTypeAndBizIdAndStatusNot(bizType, bizId, STATUS_VOID);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
        }
        ApInvoice ap = new ApInvoice();
        ap.setInvoiceNo(ledger.genNo("AP"));
        ap.setSupplierId(supplierId);
        ap.setBizType(bizType);
        ap.setBizId(bizId);
        ap.setInvoiceAmount(scale(amount));
        ap.setSettledAmount(BigDecimal.ZERO);
        ap.setStatus(STATUS_UNSETTLED);
        ap.setCreatedBy(ledger.currentUser());
        return apRepository.save(ap);
    }

    /** 作废联动：同一业务单据下所有未作废应收/应付全部置 VOID。 */
    @Transactional
    public void voidByBiz(String bizType, Long bizId) {
        for (ArInvoice ar : arRepository.findByBizTypeAndBizIdAndStatusNot(bizType, bizId, STATUS_VOID)) {
            ar.setStatus(STATUS_VOID);
            arRepository.save(ar);
        }
        for (ApInvoice ap : apRepository.findByBizTypeAndBizIdAndStatusNot(bizType, bizId, STATUS_VOID)) {
            ap.setStatus(STATUS_VOID);
            apRepository.save(ap);
        }
    }

    /** 客户未结应收合计（UNSETTLED + PARTIAL）。 */
    public BigDecimal outstanding(Long customerId) {
        if (customerId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = arRepository.sumOutstandingByCustomer(customerId);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 供应商未结应付合计。 */
    public BigDecimal outstandingPayable(Long supplierId) {
        if (supplierId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = apRepository.sumOutstandingBySupplier(supplierId);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 应收分页。 */
    public PageResult<ArInvoiceVO> listAr(Long customerId, String status, String keyword, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<ArInvoice> result = arRepository.search(customerId, blankToNull(status), blankToNull(keyword), pageable);
        Map<Long, String> names = customerNames(result.getContent().stream().map(ArInvoice::getCustomerId).toList());
        List<ArInvoiceVO> records = result.getContent().stream().map(a -> toVO(a, names)).toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    /** 应付分页。 */
    public PageResult<ApInvoiceVO> listAp(Long supplierId, String status, String keyword, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<ApInvoice> result = apRepository.search(supplierId, blankToNull(status), blankToNull(keyword), pageable);
        Map<Long, String> names = supplierNames(result.getContent().stream().map(ApInvoice::getSupplierId).toList());
        List<ApInvoiceVO> records = result.getContent().stream().map(a -> toVO(a, names)).toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    /** 手动补录应收（bizType=MANUAL，bizId=0）。 */
    @Transactional
    public ArInvoiceVO createManualAr(Long customerId, BigDecimal amount, String remark) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("应收金额必须大于0");
        }
        ArInvoice ar = generateAr(customerId, BIZ_MANUAL, 0L, amount);
        ar.setRemark(remark);
        ar = arRepository.save(ar);
        return toVO(ar, customerNames(List.of(ar.getCustomerId())));
    }

    /** 手动补录应付（bizType=MANUAL，bizId=0）。 */
    @Transactional
    public ApInvoiceVO createManualAp(Long supplierId, BigDecimal amount, String remark) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("应付金额必须大于0");
        }
        ApInvoice ap = generateAp(supplierId, BIZ_MANUAL, 0L, amount);
        ap.setRemark(remark);
        ap = apRepository.save(ap);
        return toVO(ap, supplierNames(List.of(ap.getSupplierId())));
    }

    /** 应收视图（供核销后返回/其他服务复用）。 */
    public ArInvoiceVO toVO(ArInvoice a, Map<Long, String> names) {
        BigDecimal outstanding = a.getInvoiceAmount().subtract(a.getSettledAmount());
        return new ArInvoiceVO(a.getId(), a.getInvoiceNo(), a.getCustomerId(),
                names.get(a.getCustomerId()), a.getBizType(), a.getBizId(),
                a.getInvoiceAmount(), a.getSettledAmount(), outstanding,
                a.getStatus(), a.getRemark(), a.getCreatedBy(), a.getCreatedAt());
    }

    public ApInvoiceVO toVO(ApInvoice a, Map<Long, String> names) {
        BigDecimal outstanding = a.getInvoiceAmount().subtract(a.getSettledAmount());
        return new ApInvoiceVO(a.getId(), a.getInvoiceNo(), a.getSupplierId(),
                names.get(a.getSupplierId()), a.getBizType(), a.getBizId(),
                a.getInvoiceAmount(), a.getSettledAmount(), outstanding,
                a.getStatus(), a.getRemark(), a.getCreatedBy(), a.getCreatedAt());
    }

    public Map<Long, String> customerNames(List<Long> ids) {
        Set<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return customerRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getName));
    }

    public Map<Long, String> supplierNames(List<Long> ids) {
        Set<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return supplierRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName));
    }

    /** 核销后状态：未结 -> 部分 -> 已结清。 */
    public String settleStatus(BigDecimal invoiceAmount, BigDecimal settledAmount) {
        if (settledAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return STATUS_UNSETTLED;
        }
        return settledAmount.compareTo(invoiceAmount) >= 0 ? STATUS_SETTLED : STATUS_PARTIAL;
    }

    private BigDecimal scale(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
