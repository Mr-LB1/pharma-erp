package com.pharma.compliance;

import com.pharma.approval.dto.ApprovalItemVO;
import com.pharma.approval.dto.ApprovalPendingVO;
import com.pharma.common.PageResult;
import com.pharma.purchase.entity.PurchaseOrder;
import com.pharma.purchase.entity.PurchaseOrderItem;
import com.pharma.purchase.repository.PurchaseOrderItemRepository;
import com.pharma.purchase.repository.PurchaseOrderRepository;
import com.pharma.sales.entity.SalesOrder;
import com.pharma.sales.entity.SalesOrderItem;
import com.pharma.sales.entity.SalesReturn;
import com.pharma.sales.entity.SalesReturnItem;
import com.pharma.sales.repository.SalesOrderItemRepository;
import com.pharma.sales.repository.SalesOrderRepository;
import com.pharma.sales.repository.SalesReturnItemRepository;
import com.pharma.sales.repository.SalesReturnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #27 待审批分页：数据库分页边界、排序（biz_id DESC + biz_type ASC tie-break）、
 * 批量往来取名、越界空页、size 上限 200、退货明细金额 2 位小数。
 */
class PendingPaginationTest extends AbstractComplianceTest {

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired
    private SalesOrderRepository salesOrderRepository;
    @Autowired
    private SalesOrderItemRepository salesOrderItemRepository;
    @Autowired
    private SalesReturnRepository salesReturnRepository;
    @Autowired
    private SalesReturnItemRepository salesReturnItemRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private PurchaseOrder newPurchaseOrder(String no, String amount) {
        PurchaseOrder o = new PurchaseOrder();
        o.setOrderNo(no);
        o.setSupplierId(supplierId);
        o.setOrderDate(LocalDate.now());
        o.setStatus("SUBMITTED");
        o.setTotalAmount(new BigDecimal(amount));
        o.setCreatedBy("maker");
        return purchaseOrderRepository.save(o);
    }

    private SalesOrder newSalesOrder(String no, String amount) {
        SalesOrder o = new SalesOrder();
        o.setOrderNo(no);
        o.setCustomerId(customerId);
        o.setOrderDate(LocalDate.now());
        o.setStatus("SUBMITTED");
        o.setTotalAmount(new BigDecimal(amount));
        o.setCreatedBy("maker");
        return salesOrderRepository.save(o);
    }

    private SalesReturn newSalesReturn(String no, String amount) {
        SalesReturn r = new SalesReturn();
        r.setReturnNo(no);
        r.setDeliveryId(1L);
        r.setCustomerId(customerId);
        r.setReturnDate(LocalDate.now());
        r.setStatus("SUBMITTED");
        r.setTotalAmount(new BigDecimal(amount));
        r.setCreatedBy("maker");
        return salesReturnRepository.save(r);
    }

    @Test
    void 分页边界_5单每页2条共三页() {
        long baseline = approvalService.pending(1, 1).getTotal();
        for (int i = 0; i < 5; i++) {
            newPurchaseOrder("PEND-P-" + i + "-" + System.nanoTime(), "100");
        }
        long total = baseline + 5;

        PageResult<ApprovalPendingVO> p1 = approvalService.pending(1, 2);
        PageResult<ApprovalPendingVO> p2 = approvalService.pending(2, 2);
        PageResult<ApprovalPendingVO> p3 = approvalService.pending(3, 2);

        assertThat(p1.getTotal()).isEqualTo(total);
        assertThat(p1.getSize()).isEqualTo(2);
        assertThat(p1.getRecords()).hasSize((int) Math.min(2, total));
        assertThat(p2.getRecords()).hasSize((int) Math.min(2, Math.max(0, total - 2)));
        assertThat(p3.getRecords()).hasSize((int) Math.min(2, Math.max(0, total - 4)));
    }

    @Test
    void 排序按bizId降序且同id时类型升序() {
        long baseline = approvalService.pending(1, 1).getTotal();
        long sameId = 9_000_000_001L;
        LocalDate today = LocalDate.now();
        jdbc.update("""
                INSERT INTO sales_return (id, return_no, delivery_id, customer_id, return_date, status, total_amount, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, 'SUBMITTED', ?, 'maker', now())
                """, sameId, "TIE-SR-" + sameId, 1L, customerId, today, new BigDecimal("30"));
        jdbc.update("""
                INSERT INTO purchase_order (id, order_no, supplier_id, order_date, status, total_amount, created_by, created_at)
                VALUES (?, ?, ?, ?, 'SUBMITTED', ?, 'maker', now())
                """, sameId, "TIE-PO-" + sameId, supplierId, today, new BigDecimal("10"));
        jdbc.update("""
                INSERT INTO sales_order (id, order_no, customer_id, order_date, status, total_amount, created_by, created_at)
                VALUES (?, ?, ?, ?, 'SUBMITTED', ?, 'maker', now())
                """, sameId, "TIE-SO-" + sameId, customerId, today, new BigDecimal("20"));
        jdbc.update("""
                INSERT INTO purchase_order (id, order_no, supplier_id, order_date, status, total_amount, created_by, created_at)
                VALUES (?, ?, ?, ?, 'SUBMITTED', ?, 'maker', now())
                """, sameId + 1, "TIE-PO2-" + sameId, supplierId, today, new BigDecimal("11"));

        List<String> createdTypes = approvalService.pending(1, 200).getRecords().stream()
                .filter(r -> r.bizId() == sameId || r.bizId() == sameId + 1)
                .map(r -> r.bizType() + ":" + r.bizId())
                .toList();

        assertThat(createdTypes).containsExactly(
                "PURCHASE_ORDER:" + (sameId + 1),
                "PURCHASE_ORDER:" + sameId,
                "SALES_ORDER:" + sameId,
                "SALES_RETURN:" + sameId);
        // 总数为基线 + 本次新增 4 单
        assertThat(approvalService.pending(1, 1).getTotal()).isEqualTo(baseline + 4);
    }

    @Test
    void 批量取名_明细商品名与往来单位名() {
        PurchaseOrder po = newPurchaseOrder("PEND-PO-NAME-" + System.nanoTime(), "100");
        PurchaseOrderItem poi = new PurchaseOrderItem();
        poi.setOrderId(po.getId());
        poi.setProductId(productId);
        poi.setQuantity(new BigDecimal("2"));
        poi.setUnitPrice(new BigDecimal("50"));
        poi.setAmount(new BigDecimal("100"));
        purchaseOrderItemRepository.save(poi);

        SalesOrder so = newSalesOrder("PEND-SO-NAME-" + System.nanoTime(), "60");
        SalesOrderItem soi = new SalesOrderItem();
        soi.setOrderId(so.getId());
        soi.setProductId(productId);
        soi.setQuantity(new BigDecimal("3"));
        soi.setUnitPrice(new BigDecimal("20"));
        soi.setAmount(new BigDecimal("60"));
        salesOrderItemRepository.save(soi);

        List<ApprovalPendingVO> records = approvalService.pending(1, 200).getRecords();
        ApprovalPendingVO poVO = records.stream().filter(r -> r.bizId().equals(po.getId())).findFirst().orElseThrow();
        ApprovalPendingVO soVO = records.stream().filter(r -> r.bizId().equals(so.getId())).findFirst().orElseThrow();

        assertThat(poVO.partyName()).isEqualTo("测试供应商");
        assertThat(soVO.partyName()).isEqualTo("测试客户");
        assertThat(poVO.items()).extracting(ApprovalItemVO::productName).containsExactly("测试药品");
        assertThat(soVO.items()).extracting(ApprovalItemVO::productName).containsExactly("测试药品");
        assertThat(poVO.items().get(0).quantity()).isEqualByComparingTo("2");
    }

    @Test
    void 越界页返回空记录且总数不变() {
        long baseline = approvalService.pending(1, 1).getTotal();
        PageResult<ApprovalPendingVO> far = approvalService.pending(9999, 20);

        assertThat(far.getRecords()).isEmpty();
        assertThat(far.getTotal()).isEqualTo(baseline);
        assertThat(far.getPage()).isEqualTo(9999);
    }

    @Test
    void size上限夹到200() {
        assertThat(approvalService.pending(1, 500).getSize()).isEqualTo(200);
        assertThat(approvalService.pending(1, 0).getSize()).isEqualTo(20);
        assertThat(approvalService.pending(0, -5).getPage()).isEqualTo(1);
    }

    @Test
    void 退货明细金额两位小数() {
        SalesReturn r = newSalesReturn("PEND-SR-AMT-" + System.nanoTime(), "5.00");
        SalesReturnItem item = new SalesReturnItem();
        item.setReturnId(r.getId());
        item.setProductId(productId);
        item.setBatchNo("B1");
        item.setQuantity(new BigDecimal("1.5"));
        item.setUnitPrice(new BigDecimal("3.33"));
        item.setUnitCost(new BigDecimal("3.00"));
        item.setWarehouseId(warehouseId);
        salesReturnItemRepository.save(item);

        ApprovalPendingVO vo = approvalService.pending(1, 200).getRecords().stream()
                .filter(x -> x.bizId().equals(r.getId())).findFirst().orElseThrow();

        ApprovalItemVO itemVO = vo.items().get(0);
        // 1.5 * 3.33 = 4.995 -> HALF_UP 2 位 = 5.00
        assertThat(itemVO.amount()).isEqualByComparingTo("5.00");
        assertThat(itemVO.amount().scale()).isEqualTo(2);
        assertThat(vo.partyName()).isEqualTo("测试客户");
    }
}
