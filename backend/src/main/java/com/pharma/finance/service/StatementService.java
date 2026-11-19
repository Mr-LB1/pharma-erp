package com.pharma.finance.service;

import com.pharma.base.repository.CustomerRepository;
import com.pharma.common.BusinessException;
import com.pharma.finance.dto.StatementItemVO;
import com.pharma.finance.dto.StatementVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户应收对账：期初余额 + 本期发货（含手工补录）- 本期退货 - 本期收款 = 期末余额。
 *
 * <p>口径（批次十 / 决策 4）：对账单按<b>业务单据日期</b>分期，而非单据生成时间：
 * 销售出库取 {@code sales_delivery.delivery_date}，销售退货取 {@code sales_return.return_date}，
 * 收款取 {@code receipt.receipt_date}（历史数据回填 created_at 日期），
 * 手工补录应收无业务单据则回落 created_at 日期。期初 / 本期 / 明细使用同一有效日期表达式，
 * 恒等式 {@code closing = opening + delivery - return - receipt} 不受跨期影响。</p>
 */
@Service
public class StatementService {

    private final JdbcTemplate jdbc;
    private final CustomerRepository customerRepository;

    public StatementService(JdbcTemplate jdbc, CustomerRepository customerRepository) {
        this.jdbc = jdbc;
        this.customerRepository = customerRepository;
    }

    public StatementVO ar(Long customerId, LocalDate startDate, LocalDate endDate) {
        if (customerId == null) {
            throw BusinessException.badRequest("客户不能为空");
        }
        if (customerRepository.findById(customerId).isEmpty()) {
            throw BusinessException.badRequest("客户不存在: " + customerId);
        }
        LocalDate start = startDate == null ? LocalDate.of(1970, 1, 1) : startDate;
        LocalDate end = endDate == null ? LocalDate.now() : endDate;
        if (end.isBefore(start)) {
            throw BusinessException.badRequest("结束日期不能早于开始日期");
        }

        BigDecimal openingInvoices = queryAmount("""
                SELECT COALESCE(SUM(i.invoice_amount), 0)
                FROM ar_invoice i
                LEFT JOIN sales_delivery d ON i.biz_type = 'SALES_DELIVERY' AND d.id = i.biz_id
                LEFT JOIN sales_return r ON i.biz_type = 'SALES_RETURN' AND r.id = i.biz_id
                WHERE i.customer_id = ? AND i.status <> 'VOID'
                  AND COALESCE(d.delivery_date, r.return_date, CAST(i.created_at AS DATE)) < ?
                """, customerId, start);
        BigDecimal openingReceipts = queryAmount("""
                SELECT COALESCE(SUM(r.amount), 0) FROM receipt r
                WHERE r.customer_id = ? AND r.status = 'ACTIVE' AND r.receipt_date < ?
                """, customerId, start);
        BigDecimal opening = openingInvoices.subtract(openingReceipts);

        BigDecimal delivery = queryAmount("""
                SELECT COALESCE(SUM(i.invoice_amount), 0)
                FROM ar_invoice i
                LEFT JOIN sales_delivery d ON i.biz_type = 'SALES_DELIVERY' AND d.id = i.biz_id
                LEFT JOIN sales_return r ON i.biz_type = 'SALES_RETURN' AND r.id = i.biz_id
                WHERE i.customer_id = ? AND i.status <> 'VOID'
                  AND (i.biz_type = 'SALES_DELIVERY' OR i.biz_type = 'MANUAL')
                  AND COALESCE(d.delivery_date, r.return_date, CAST(i.created_at AS DATE)) >= ?
                  AND COALESCE(d.delivery_date, r.return_date, CAST(i.created_at AS DATE)) <= ?
                """, customerId, start, end);
        BigDecimal returnAmount = queryAmount("""
                SELECT COALESCE(-SUM(i.invoice_amount), 0)
                FROM ar_invoice i
                LEFT JOIN sales_delivery d ON i.biz_type = 'SALES_DELIVERY' AND d.id = i.biz_id
                LEFT JOIN sales_return r ON i.biz_type = 'SALES_RETURN' AND r.id = i.biz_id
                WHERE i.customer_id = ? AND i.status <> 'VOID'
                  AND i.biz_type = 'SALES_RETURN'
                  AND COALESCE(d.delivery_date, r.return_date, CAST(i.created_at AS DATE)) >= ?
                  AND COALESCE(d.delivery_date, r.return_date, CAST(i.created_at AS DATE)) <= ?
                """, customerId, start, end);
        BigDecimal receiptAmount = queryAmount("""
                SELECT COALESCE(SUM(r.amount), 0) FROM receipt r
                WHERE r.customer_id = ? AND r.status = 'ACTIVE'
                  AND r.receipt_date >= ? AND r.receipt_date <= ?
                """, customerId, start, end);

        BigDecimal closing = opening.add(delivery).subtract(returnAmount).subtract(receiptAmount);

        List<StatementItemVO> items = new ArrayList<>();
        jdbc.query("""
                SELECT COALESCE(d.delivery_date, r.return_date, CAST(i.created_at AS DATE)) AS biz_date,
                       i.biz_type, i.invoice_no, i.invoice_amount, i.remark
                FROM ar_invoice i
                LEFT JOIN sales_delivery d ON i.biz_type = 'SALES_DELIVERY' AND d.id = i.biz_id
                LEFT JOIN sales_return r ON i.biz_type = 'SALES_RETURN' AND r.id = i.biz_id
                WHERE i.customer_id = ? AND i.status <> 'VOID'
                  AND COALESCE(d.delivery_date, r.return_date, CAST(i.created_at AS DATE)) >= ?
                  AND COALESCE(d.delivery_date, r.return_date, CAST(i.created_at AS DATE)) <= ?
                ORDER BY biz_date ASC, i.id ASC
                """, (rs, rowNum) -> {
            String bizType = rs.getString("biz_type");
            String type = "SALES_RETURN".equals(bizType) ? "RETURN" : "DELIVERY";
            items.add(new StatementItemVO(rs.getDate("biz_date").toLocalDate(), type,
                    rs.getString("invoice_no"), rs.getBigDecimal("invoice_amount"), rs.getString("remark")));
            return null;
        }, customerId, start, end);

        jdbc.query("""
                SELECT r.receipt_date AS biz_date, r.receipt_no, r.amount, r.remark
                FROM receipt r
                WHERE r.customer_id = ? AND r.status = 'ACTIVE'
                  AND r.receipt_date >= ? AND r.receipt_date <= ?
                ORDER BY r.receipt_date ASC, r.id ASC
                """, (rs, rowNum) -> {
            items.add(new StatementItemVO(rs.getDate("biz_date").toLocalDate(), "RECEIPT",
                    rs.getString("receipt_no"), rs.getBigDecimal("amount").negate(), rs.getString("remark")));
            return null;
        }, customerId, start, end);

        items.sort((a, b) -> {
            int cmp = a.date().compareTo(b.date());
            return cmp != 0 ? cmp : a.type().compareTo(b.type());
        });

        String customerName = customerRepository.findById(customerId)
                .map(c -> c.getName()).orElse(null);
        return new StatementVO(customerId, customerName, start, end, opening,
                delivery, returnAmount, receiptAmount, closing, items);
    }

    private BigDecimal queryAmount(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }
}
