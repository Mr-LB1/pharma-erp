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
import com.pharma.system.service.AuditLogService;
import jakarta.persistence.EntityManager;
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
    /** JPA 实体管理器：作废联动时先脱离一级缓存再锁查询，强制重读数据库最新已核销金额。 */
    private final EntityManager entityManager;
    /** 审计日志服务：手工补录与作废留痕（与业务同事务，失败一起回滚）。 */
    private final AuditLogService auditLogService;

    public ArApService(ArInvoiceRepository arRepository,
                       ApInvoiceRepository apRepository,
                       CustomerRepository customerRepository,
                       SupplierRepository supplierRepository,
                       InventoryLedgerService ledger,
                       EntityManager entityManager,
                       AuditLogService auditLogService) {
        this.arRepository = arRepository;
        this.apRepository = apRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
        this.ledger = ledger;
        this.entityManager = entityManager;
        this.auditLogService = auditLogService;
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

    /**
     * 作废联动：同一业务单据下所有未作废应收/应付全部置 VOID；
     * 任一单据已发生核销（settled_amount > 0）时拒绝作废，避免已收/已付款项凭空消失。
     * 校验前按 ID 升序对目标单据加行级悲观锁并强制重读，与收/付款核销的 findByIdForUpdate 互斥，
     * 消除「校验时未核销、置 VOID 前被并发核销」的 TOCTOU；每张被作废发票均写审计日志留痕。
     *
     * @param bizType 业务单据类型（如 SALES_DELIVERY / PURCHASE_RECEIVE）
     * @param bizId   业务单据 ID
     * @throws BusinessException 存在已核销的应收/应付时抛出 400
     */
    @Transactional
    public void voidByBiz(String bizType, Long bizId) {
        // 1. 先取同业务单据下全部未作废应收/应付候选：此步只用于定位 ID，状态稍后以锁后重读为准
        List<ArInvoice> candidateArInvoiceList =
                arRepository.findByBizTypeAndBizIdAndStatusNot(bizType, bizId, STATUS_VOID);
        List<ApInvoice> candidateApInvoiceList =
                apRepository.findByBizTypeAndBizIdAndStatusNot(bizType, bizId, STATUS_VOID);
        List<Long> arInvoiceIdList = candidateArInvoiceList.stream().map(ArInvoice::getId).sorted().toList();
        List<Long> apInvoiceIdList = candidateApInvoiceList.stream().map(ApInvoice::getId).sorted().toList();

        // 2. 脱离持久化上下文：首查已把候选实体放进一级缓存，Hibernate 锁查询命中已缓存原实例时不会刷新状态，
        //    WHY: 必须先 detach 再 findByIdForUpdate，否则读到的仍是旧 settled_amount，TOCTOU 依然存在
        candidateArInvoiceList.forEach(entityManager::detach);
        candidateApInvoiceList.forEach(entityManager::detach);

        // 3. 按 ID 升序逐行加悲观写锁重读：与 Receipt/Payment 核销的加锁顺序一致，避免并发死锁
        List<ArInvoice> arInvoiceList = new ArrayList<>(arInvoiceIdList.size());
        for (Long arInvoiceId : arInvoiceIdList) {
            arRepository.findByIdForUpdate(arInvoiceId).ifPresent(arInvoiceList::add);
        }
        List<ApInvoice> apInvoiceList = new ArrayList<>(apInvoiceIdList.size());
        for (Long apInvoiceId : apInvoiceIdList) {
            apRepository.findByIdForUpdate(apInvoiceId).ifPresent(apInvoiceList::add);
        }

        // 4. 锁后校验：任一已核销即拒绝作废，已核销表示已发生收付款，强行作废会导致账实不符
        for (ArInvoice arInvoice : arInvoiceList) {
            if (arInvoice.getSettledAmount().signum() > 0) {
                throw BusinessException.badRequest("已核销不能作废: " + arInvoice.getInvoiceNo());
            }
        }
        for (ApInvoice apInvoice : apInvoiceList) {
            if (apInvoice.getSettledAmount().signum() > 0) {
                throw BusinessException.badRequest("已核销不能作废: " + apInvoice.getInvoiceNo());
            }
        }

        // 5. 全部校验通过后统一置 VOID，并逐张发票写审计留痕（发票号 + 业务单据 + 金额）
        String operator = ledger.currentUser();
        for (ArInvoice arInvoice : arInvoiceList) {
            String beforeStatus = arInvoice.getStatus();
            arInvoice.setStatus(STATUS_VOID);
            arRepository.save(arInvoice);
            auditLogService.log(operator, "VOID_AR_INVOICE", "AR_INVOICE", arInvoice.getId(),
                    "作废应收单 " + arInvoice.getInvoiceNo() + "，业务单据 " + bizType + ":" + bizId
                            + "，金额 " + arInvoice.getInvoiceAmount().toPlainString(),
                    AuditLogService.json("status", beforeStatus, "settledAmount", arInvoice.getSettledAmount()),
                    AuditLogService.json("status", STATUS_VOID));
        }
        for (ApInvoice apInvoice : apInvoiceList) {
            String beforeStatus = apInvoice.getStatus();
            apInvoice.setStatus(STATUS_VOID);
            apRepository.save(apInvoice);
            auditLogService.log(operator, "VOID_AP_INVOICE", "AP_INVOICE", apInvoice.getId(),
                    "作废应付单 " + apInvoice.getInvoiceNo() + "，业务单据 " + bizType + ":" + bizId
                            + "，金额 " + apInvoice.getInvoiceAmount().toPlainString(),
                    AuditLogService.json("status", beforeStatus, "settledAmount", apInvoice.getSettledAmount()),
                    AuditLogService.json("status", STATUS_VOID));
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

    /**
     * 手动补录应收（bizType=MANUAL，bizId=0），并写审计日志留痕。
     *
     * @param customerId 客户 ID，不可为空（由 generateAr 校验）
     * @param amount     应收金额，必须大于 0，落库保留两位小数
     * @param remark     备注，可空
     * @return 手工应收单视图，含客户名称与未结金额
     * @throws BusinessException 金额为空或不大于 0 时抛出 400
     */
    @Transactional
    public ArInvoiceVO createManualAr(Long customerId, BigDecimal amount, String remark) {
        // 1. 金额校验：手工补录必须为正数
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("应收金额必须大于0");
        }

        // 2. 生成手工应收并写入备注
        ArInvoice ar = generateAr(customerId, BIZ_MANUAL, 0L, amount);
        ar.setRemark(remark);
        ar = arRepository.save(ar);

        // 3. 审计留痕：手工补录属于人工干预，记录客户、金额与备注
        auditLogService.log(ledger.currentUser(), "CREATE_MANUAL_AR", "AR_INVOICE", ar.getId(),
                "手工补录应收 " + ar.getInvoiceNo() + "，客户 " + ar.getCustomerId()
                        + "，金额 " + ar.getInvoiceAmount().toPlainString()
                        + (remark == null || remark.isBlank() ? "" : "，备注 " + remark),
                null, AuditLogService.json("invoiceNo", ar.getInvoiceNo(), "customerId", ar.getCustomerId(),
                        "invoiceAmount", ar.getInvoiceAmount(), "bizType", BIZ_MANUAL, "remark", remark));

        // 4. 组装返回视图
        return toVO(ar, customerNames(List.of(ar.getCustomerId())));
    }

    /**
     * 手动补录应付（bizType=MANUAL，bizId=0），并写审计日志留痕。
     *
     * @param supplierId 供应商 ID，不可为空（由 generateAp 校验）
     * @param amount     应付金额，必须大于 0，落库保留两位小数
     * @param remark     备注，可空
     * @return 手工应付单视图，含供应商名称与未结金额
     * @throws BusinessException 金额为空或不大于 0 时抛出 400
     */
    @Transactional
    public ApInvoiceVO createManualAp(Long supplierId, BigDecimal amount, String remark) {
        // 1. 金额校验：手工补录必须为正数
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("应付金额必须大于0");
        }

        // 2. 生成手工应付并写入备注
        ApInvoice ap = generateAp(supplierId, BIZ_MANUAL, 0L, amount);
        ap.setRemark(remark);
        ap = apRepository.save(ap);

        // 3. 审计留痕：手工补录属于人工干预，记录供应商、金额与备注
        auditLogService.log(ledger.currentUser(), "CREATE_MANUAL_AP", "AP_INVOICE", ap.getId(),
                "手工补录应付 " + ap.getInvoiceNo() + "，供应商 " + ap.getSupplierId()
                        + "，金额 " + ap.getInvoiceAmount().toPlainString()
                        + (remark == null || remark.isBlank() ? "" : "，备注 " + remark),
                null, AuditLogService.json("invoiceNo", ap.getInvoiceNo(), "supplierId", ap.getSupplierId(),
                        "invoiceAmount", ap.getInvoiceAmount(), "bizType", BIZ_MANUAL, "remark", remark));

        // 4. 组装返回视图
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
