package com.pharma.approval.repository;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * #27 待审批单据分页查询（数据库分页，替代既有的「全量查询 + 内存排序分页」）。
 * 三张业务表 UNION ALL 后用 (biz_id DESC, biz_type ASC) 统一排序，LIMIT/OFFSET 下推数据库。
 * 仅读取单据头（明细/商品/往来单位由调用方按当前页 bizId 批量加载，N+1 消失）。
 *
 * <p>批次十（决策 5）：按审批人角色过滤。每行按金额命中 approval_config 解析出所需角色
 * （无配置回落 APPROVER，与 ApprovalLevelResolver 同口径），SQL 中直接以 IN (角色列表) 收窄，
 * 过滤与分页/计数在同一条 SQL 内完成，不会退化为全量内存分页。</p>
 */
@Repository
public class ApprovalPendingQueryRepository {

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public ApprovalPendingQueryRepository(JdbcTemplate jdbc, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    /** 待审批单据头（统一视图） */
    public record PendingRow(String bizType,
                             Long bizId,
                             String orderNo,
                             LocalDate orderDate,
                             BigDecimal totalAmount,
                             Long partyId,
                             String createdBy) {
    }

    /**
     * 统一视图：附带该单命中的审批角色（approver_role）。
     * 角色解析与 ApprovalLevelResolver 一致：区间命中且启用，层级倒序取最严格一档，无命中回落 APPROVER。
     * 批次十四：采购入库 / 销售出库 / 采购退货 / 仓库调拨 一并进入待审视图。
     */
    private static final String UNION_SQL = """
            SELECT * FROM (
                SELECT 'PURCHASE_ORDER' AS biz_type, id AS biz_id, order_no, order_date, total_amount,
                       supplier_id AS party_id, created_by, %s AS approver_role
                FROM purchase_order p WHERE p.status = 'SUBMITTED'
                UNION ALL
                SELECT 'SALES_ORDER' AS biz_type, id AS biz_id, order_no, order_date, total_amount,
                       customer_id AS party_id, created_by, %s AS approver_role
                FROM sales_order s WHERE s.status = 'SUBMITTED'
                UNION ALL
                SELECT 'SALES_RETURN' AS biz_type, id AS biz_id, return_no AS order_no, return_date AS order_date,
                       total_amount, customer_id AS party_id, created_by, %s AS approver_role
                FROM sales_return r WHERE r.status = 'SUBMITTED'
                UNION ALL
                SELECT 'PURCHASE_RECEIVE' AS biz_type, pr.id AS biz_id, pr.receive_no AS order_no,
                       pr.receive_date AS order_date, pr.total_amount,
                       (SELECT po.supplier_id FROM purchase_order po WHERE po.id = pr.order_id) AS party_id,
                       pr.created_by, %s AS approver_role
                FROM purchase_receive pr WHERE pr.status = 'SUBMITTED'
                UNION ALL
                SELECT 'SALES_DELIVERY' AS biz_type, sd.id AS biz_id, sd.delivery_no AS order_no,
                       sd.delivery_date AS order_date, sd.total_amount,
                       (SELECT so.customer_id FROM sales_order so WHERE so.id = sd.order_id) AS party_id,
                       sd.created_by, %s AS approver_role
                FROM sales_delivery sd WHERE sd.status = 'SUBMITTED'
                UNION ALL
                SELECT 'PURCHASE_RETURN' AS biz_type, pret.id AS biz_id, pret.return_no AS order_no,
                       pret.return_date AS order_date, pret.total_amount, pret.supplier_id AS party_id,
                       pret.created_by, %s AS approver_role
                FROM purchase_return pret WHERE pret.status = 'SUBMITTED'
                UNION ALL
                SELECT 'STOCK_TRANSFER' AS biz_type, st.id AS biz_id, st.transfer_no AS order_no,
                       st.transfer_date AS order_date, st.total_amount, NULL::BIGINT AS party_id,
                       st.created_by, %s AS approver_role
                FROM stock_transfer st WHERE st.status = 'SUBMITTED'
            ) t
            """;

    /** 每类单据按金额解析所需审批角色（表别名 p / s / r 与 UNION 分支一致）。 */
    private static String roleExpr(String alias, String bizType) {
        return """
                COALESCE((SELECT c.approver_role FROM approval_config c
                          WHERE c.biz_type = '%s' AND c.enabled
                            AND %s.total_amount >= c.min_amount AND %s.total_amount <= c.max_amount
                          ORDER BY c.level DESC LIMIT 1), 'APPROVER')
                """.formatted(bizType, alias, alias).trim();
    }

    private static final String BASE_SQL = UNION_SQL.formatted(
            roleExpr("p", "PURCHASE_ORDER"),
            roleExpr("s", "SALES_ORDER"),
            roleExpr("r", "SALES_RETURN"),
            roleExpr("pr", "PURCHASE_RECEIVE"),
            roleExpr("sd", "SALES_DELIVERY"),
            roleExpr("pret", "PURCHASE_RETURN"),
            roleExpr("st", "STOCK_TRANSFER"));

    /**
     * 计数：roles 为 null 表示不过滤（内部调用 / ADMIN）；空列表表示无权查看任何待审单。
     */
    public long count(List<String> roles) {
        flushIfInTransaction();
        if (roles != null && roles.isEmpty()) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM (" + BASE_SQL + roleFilter(roles) + ") x";
        Long total = jdbc.queryForObject(sql, Long.class, roleArgs(roles));
        return total == null ? 0 : total;
    }

    public List<PendingRow> page(List<String> roles, int offset, int limit) {
        flushIfInTransaction();
        if (roles != null && roles.isEmpty()) {
            return List.of();
        }
        String sql = BASE_SQL + roleFilter(roles)
                + " ORDER BY t.biz_id DESC, t.biz_type ASC LIMIT ? OFFSET ?";
        Object[] args = append(roleArgs(roles), limit, offset);
        return jdbc.query(sql, (rs, rowNum) -> new PendingRow(
                rs.getString("biz_type"),
                rs.getLong("biz_id"),
                rs.getString("order_no"),
                rs.getDate("order_date") == null ? null : rs.getDate("order_date").toLocalDate(),
                rs.getBigDecimal("total_amount"),
                rs.getObject("party_id", Long.class),
                rs.getString("created_by")), args);
    }

    private String roleFilter(List<String> roles) {
        if (roles == null) {
            return "";
        }
        return " WHERE t.approver_role IN (" + String.join(",", java.util.Collections.nCopies(roles.size(), "?")) + ")";
    }

    private Object[] roleArgs(List<String> roles) {
        return roles == null ? new Object[0] : roles.toArray();
    }

    private static Object[] append(Object[] base, Object... extra) {
        Object[] all = new Object[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    /**
     * 同事务读己写：同一事务内先 save 再查询时，JPA 未必已 flush，JdbcTemplate 会读不到新行。
     */
    private void flushIfInTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.flush();
        }
    }
}
