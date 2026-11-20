package com.pharma.reports.service;

import com.pharma.common.PageResult;
import com.pharma.reports.dto.DashboardVO;
import com.pharma.reports.dto.GrossProfitVO;
import com.pharma.reports.dto.InventoryBalanceVO;
import com.pharma.reports.dto.NearExpiryItemVO;
import com.pharma.reports.dto.StockSummaryVO;
import com.pharma.reports.dto.TrendPointVO;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基础报表服务：进销存汇总 / 近效期清单 / 库存余额。
 * 基于 stock_transaction / stock 直接 SQL 聚合，与 append-only 流水一致。
 * 过滤条件在 Java 侧动态拼接，避免 NULL 命名参数的类型推断问题。
 */
@Service
public class ReportService {

    private static final int DEFAULT_NEAR_EXPIRY_DAYS = 30;

    private final NamedParameterJdbcTemplate jdbc;

    public ReportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 进销存汇总：期初/入库/出库/期末，按商品（基于 stock_transaction）；#25 支持仓库筛选与金额列。
     * 数量口径按 t.location_id 过滤仓库；金额口径取 cost_ledger（商品级移动加权，无仓库维度，按 product_id + op_time 聚合）。
     */
    public List<StockSummaryVO> stockSummary(Long productId, LocalDate startDate, LocalDate endDate, Long warehouseId) {
        return queryStockSummary(productId, startDate, endDate, warehouseId, null, null);
    }

    /** 进销存汇总分页（P2-7）：保持与全量导出口径一致，total 为商品去重数。 */
    public PageResult<StockSummaryVO> stockSummaryPage(Long productId, LocalDate startDate, LocalDate endDate,
                                                       Long warehouseId, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);

        MapSqlParameterSource countParams = stockSummaryPeriodParams(startDate, endDate);
        String where = stockSummaryWhere(productId, warehouseId, countParams);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT t.product_id) FROM stock_transaction t"
                        + " JOIN product p ON p.id = t.product_id" + where,
                countParams, Long.class);

        List<StockSummaryVO> records = queryStockSummary(productId, startDate, endDate, warehouseId, s, (p - 1) * s);
        return new PageResult<>(records, total == null ? 0 : total, p, s);
    }

    private MapSqlParameterSource stockSummaryPeriodParams(LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate == null
                ? LocalDateTime.of(1970, 1, 1, 0, 0)
                : startDate.atStartOfDay();
        LocalDateTime endExclusive = endDate == null
                ? LocalDate.now().plusDays(1).atStartOfDay()
                : endDate.plusDays(1).atStartOfDay();
        return new MapSqlParameterSource()
                .addValue("start", start)
                .addValue("end", endExclusive);
    }

    private String stockSummaryWhere(Long productId, Long warehouseId, MapSqlParameterSource params) {
        List<String> conds = new ArrayList<>();
        if (productId != null) {
            conds.add("t.product_id = :productId");
            params.addValue("productId", productId);
        }
        if (warehouseId != null) {
            conds.add("t.location_id = :warehouseId");
            params.addValue("warehouseId", warehouseId);
        }
        return conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);
    }

    private List<StockSummaryVO> queryStockSummary(Long productId, LocalDate startDate, LocalDate endDate,
                                                   Long warehouseId, Integer limit, Integer offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.product_id AS product_id,
                       p.code AS product_code,
                       p.generic_name AS product_name,
                       p.generic_name AS generic_name,
                       p.trade_name AS trade_name,
                       p.spec AS spec,
                       p.manufacturer AS manufacturer,
                       COALESCE(SUM(CASE WHEN t.op_time < :start THEN t.quantity ELSE 0 END), 0) AS opening_qty,
                       COALESCE(SUM(CASE WHEN t.op_time >= :start AND t.op_time < :end AND t.quantity > 0 THEN t.quantity ELSE 0 END), 0) AS inbound_qty,
                       COALESCE(SUM(CASE WHEN t.op_time >= :start AND t.op_time < :end AND t.quantity < 0 THEN -t.quantity ELSE 0 END), 0) AS outbound_qty,
                       COALESCE(c.opening_amount, 0) AS opening_amount,
                       COALESCE(c.inbound_amount, 0) AS inbound_amount,
                       COALESCE(c.outbound_amount, 0) AS outbound_amount
                FROM stock_transaction t
                JOIN product p ON p.id = t.product_id
                LEFT JOIN (
                    SELECT product_id,
                           SUM(CASE WHEN op_time < :start THEN amount ELSE 0 END) AS opening_amount,
                           SUM(CASE WHEN op_time >= :start AND op_time < :end AND amount > 0 THEN amount ELSE 0 END) AS inbound_amount,
                           SUM(CASE WHEN op_time >= :start AND op_time < :end AND amount < 0 THEN -amount ELSE 0 END) AS outbound_amount
                    FROM cost_ledger
                    GROUP BY product_id
                ) c ON c.product_id = t.product_id
                """);
        MapSqlParameterSource params = stockSummaryPeriodParams(startDate, endDate);
        sql.append(stockSummaryWhere(productId, warehouseId, params));
        sql.append(" GROUP BY t.product_id, p.code, p.generic_name, p.trade_name, p.spec, p.manufacturer, ")
                .append("c.opening_amount, c.inbound_amount, c.outbound_amount ORDER BY t.product_id");
        if (limit != null) {
            sql.append(" LIMIT :limit OFFSET :offset");
            params.addValue("limit", limit).addValue("offset", offset);
        }

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            BigDecimal opening = rs.getBigDecimal("opening_qty");
            BigDecimal inbound = rs.getBigDecimal("inbound_qty");
            BigDecimal outbound = rs.getBigDecimal("outbound_qty");
            BigDecimal closing = opening.add(inbound).subtract(outbound);
            BigDecimal openingAmount = rs.getBigDecimal("opening_amount");
            BigDecimal inboundAmount = rs.getBigDecimal("inbound_amount");
            BigDecimal outboundAmount = rs.getBigDecimal("outbound_amount");
            BigDecimal closingAmount = openingAmount.add(inboundAmount).subtract(outboundAmount);
            return new StockSummaryVO(
                    rs.getLong("product_id"),
                    rs.getString("product_code"),
                    rs.getString("product_name"),
                    rs.getString("generic_name"),
                    rs.getString("trade_name"),
                    rs.getString("spec"),
                    rs.getString("manufacturer"),
                    opening, inbound, outbound, closing,
                    openingAmount, inboundAmount, outboundAmount, closingAmount);
        });
    }

    /** 近效期清单：批次 + 效期 + 剩余天数（含已过期，daysLeft 为负） */
    public List<NearExpiryItemVO> nearExpiryList(int days) {
        int d = days <= 0 ? DEFAULT_NEAR_EXPIRY_DAYS : days;
        LocalDate today = LocalDate.now();
        LocalDate expireBefore = today.plusDays(d);

        String sql = """
                SELECT s.product_id AS product_id,
                       p.code AS product_code,
                       p.generic_name AS product_name,
                       p.generic_name AS generic_name,
                       p.trade_name AS trade_name,
                       p.spec AS spec,
                       p.manufacturer AS manufacturer,
                       s.batch_no AS batch_no,
                       s.expire_date AS expire_date,
                       s.produce_date AS produce_date,
                       s.quantity AS quantity
                FROM stock s
                JOIN product p ON p.id = s.product_id
                WHERE s.quantity > 0 AND s.expire_date <= :expireBefore
                ORDER BY s.expire_date ASC, s.batch_no ASC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("expireBefore", expireBefore);
        return jdbc.query(sql, params, (rs, rowNum) -> {
            LocalDate expireDate = rs.getDate("expire_date").toLocalDate();
            LocalDate produceDate = rs.getDate("produce_date") == null
                    ? null : rs.getDate("produce_date").toLocalDate();
            return new NearExpiryItemVO(
                    rs.getLong("product_id"),
                    rs.getString("product_code"),
                    rs.getString("product_name"),
                    rs.getString("generic_name"),
                    rs.getString("trade_name"),
                    rs.getString("spec"),
                    rs.getString("manufacturer"),
                    rs.getString("batch_no"),
                    expireDate,
                    produceDate,
                    rs.getBigDecimal("quantity"),
                    ChronoUnit.DAYS.between(today, expireDate));
        });
    }

    /** 库存余额（分页） */
    public PageResult<InventoryBalanceVO> inventoryBalance(Long productId, Long warehouseId, int page, int size) {        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);

        List<String> conds = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (productId != null) {
            conds.add("s.product_id = :productId");
            params.addValue("productId", productId);
        }
        if (warehouseId != null) {
            conds.add("s.location_id = :warehouseId");
            params.addValue("warehouseId", warehouseId);
        }
        String where = conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock s" + where, params, Long.class);

        String sql = """
                SELECT s.product_id AS product_id,
                       p.code AS product_code,
                       p.generic_name AS product_name,
                       p.generic_name AS generic_name,
                       p.trade_name AS trade_name,
                       p.spec AS spec,
                       p.manufacturer AS manufacturer,
                       s.batch_no AS batch_no,
                       s.expire_date AS expire_date,
                       s.location_id AS location_id,
                       w.name AS warehouse_name,
                       s.quantity AS quantity,
                       s.unit_cost AS unit_cost
                FROM stock s
                JOIN product p ON p.id = s.product_id
                LEFT JOIN warehouse w ON w.id = s.location_id
                """ + where + " ORDER BY s.id ASC LIMIT :limit OFFSET :offset";
        params.addValue("limit", s).addValue("offset", (p - 1) * s);

        List<InventoryBalanceVO> records = jdbc.query(sql, params, (rs, rowNum) ->
                new InventoryBalanceVO(
                        rs.getLong("product_id"),
                        rs.getString("product_code"),
                        rs.getString("product_name"),
                        rs.getString("generic_name"),
                        rs.getString("trade_name"),
                        rs.getString("spec"),
                        rs.getString("manufacturer"),
                        rs.getString("batch_no"),
                        rs.getDate("expire_date").toLocalDate(),
                        rs.getLong("location_id"),
                        rs.getString("warehouse_name"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_cost")));

        return new PageResult<>(records, total == null ? 0 : total, p, s);
    }

    /**
     * 毛利报表（P1-26）：销售额（订单单价） - 成本（出库结转成本），按商品汇总。
     * 执行态口径（批次十四）：新单据终态为 COMPLETED，历史单据为 ACTIVE（V26 已归一），二者并集才是已执行。
     */
    public List<GrossProfitVO> grossProfit(Long productId, LocalDate startDate, LocalDate endDate) {
        LocalDate start = startDate == null ? LocalDate.of(1970, 1, 1) : startDate;
        LocalDate end = endDate == null ? LocalDate.now() : endDate;

        StringBuilder sql = new StringBuilder("""
                SELECT p.id AS product_id,
                       p.code AS product_code,
                       p.generic_name AS product_name,
                       COALESCE(SUM(di.quantity), 0) AS qty,
                       COALESCE(SUM(di.quantity * oi.unit_price), 0) AS revenue,
                       COALESCE(SUM(di.quantity * di.unit_cost), 0) AS cost
                FROM sales_delivery_item di
                JOIN sales_delivery d ON d.id = di.delivery_id AND d.status IN ('COMPLETED', 'ACTIVE')
                JOIN sales_order_item oi ON oi.order_id = d.order_id AND oi.product_id = di.product_id
                JOIN product p ON p.id = di.product_id
                WHERE d.delivery_date BETWEEN :start AND :end
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", start)
                .addValue("end", end);
        if (productId != null) {
            sql.append(" AND di.product_id = :productId ");
            params.addValue("productId", productId);
        }
        sql.append(" GROUP BY p.id, p.code, p.generic_name ORDER BY revenue DESC");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            BigDecimal revenue = rs.getBigDecimal("revenue");
            BigDecimal cost = rs.getBigDecimal("cost");
            BigDecimal profit = revenue.subtract(cost);
            BigDecimal margin = revenue.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : profit.multiply(new BigDecimal("100"))
                    .divide(revenue, 2, java.math.RoundingMode.HALF_UP);
            return new GrossProfitVO(rs.getLong("product_id"), rs.getString("product_code"),
                    rs.getString("product_name"), rs.getBigDecimal("qty"), revenue, cost, profit, margin);
        });
    }

    /** 近效期分页（P1-28 分页 + 导出共用数据源）。 */
    public PageResult<NearExpiryItemVO> nearExpiryPage(int days, int page, int size) {
        int d = days <= 0 ? DEFAULT_NEAR_EXPIRY_DAYS : days;
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        LocalDate today = LocalDate.now();
        LocalDate expireBefore = today.plusDays(d);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("expireBefore", expireBefore)
                .addValue("limit", s)
                .addValue("offset", (p - 1) * s);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock s WHERE s.quantity > 0 AND s.expire_date <= :expireBefore",
                params, Long.class);

        String sql = """
                SELECT s.product_id AS product_id,
                       p.code AS product_code,
                       p.generic_name AS product_name,
                       p.generic_name AS generic_name,
                       p.trade_name AS trade_name,
                       p.spec AS spec,
                       p.manufacturer AS manufacturer,
                       s.batch_no AS batch_no,
                       s.expire_date AS expire_date,
                       s.produce_date AS produce_date,
                       s.quantity AS quantity
                FROM stock s
                JOIN product p ON p.id = s.product_id
                WHERE s.quantity > 0 AND s.expire_date <= :expireBefore
                ORDER BY s.expire_date ASC, s.batch_no ASC
                LIMIT :limit OFFSET :offset
                """;
        List<NearExpiryItemVO> records = jdbc.query(sql, params, (rs, rowNum) -> {
            LocalDate expireDate = rs.getDate("expire_date").toLocalDate();
            LocalDate produceDate = rs.getDate("produce_date") == null
                    ? null : rs.getDate("produce_date").toLocalDate();
            return new NearExpiryItemVO(
                    rs.getLong("product_id"),
                    rs.getString("product_code"),
                    rs.getString("product_name"),
                    rs.getString("generic_name"),
                    rs.getString("trade_name"),
                    rs.getString("spec"),
                    rs.getString("manufacturer"),
                    rs.getString("batch_no"),
                    expireDate,
                    produceDate,
                    rs.getBigDecimal("quantity"),
                    ChronoUnit.DAYS.between(today, expireDate));
        });
        return new PageResult<>(records, total == null ? 0 : total, p, s);
    }

    /** 工作台 Dashboard（P1-29）：卡片数字 + 跳转路径。 */
    public DashboardVO dashboard() {
        LocalDate today = LocalDate.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("expireBefore", today.plusDays(DEFAULT_NEAR_EXPIRY_DAYS))
                .addValue("licenseBefore", today.plusDays(60))
                .addValue("today", today);

        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_record WHERE status = 'PENDING'", params, Long.class);
        Long nearExpiry = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock WHERE quantity > 0 AND expire_date <= :expireBefore",
                params, Long.class);
        Long licenseExpiring = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM customer WHERE status = 'ACTIVE'
                          AND license_expire IS NOT NULL AND license_expire <= :licenseBefore)
                     + (SELECT COUNT(*) FROM supplier WHERE status = 'ACTIVE'
                          AND license_expire IS NOT NULL AND license_expire <= :licenseBefore)
                """, params, Long.class);
        BigDecimal todayPurchase = jdbc.queryForObject("""
                SELECT COALESCE(SUM(ri.quantity * ri.unit_cost), 0)
                FROM purchase_receive_item ri
                JOIN purchase_receive r ON r.id = ri.receive_id AND r.status IN ('COMPLETED', 'ACTIVE')
                WHERE r.receive_date = :today
                """, params, BigDecimal.class);
        BigDecimal todaySales = jdbc.queryForObject("""
                SELECT COALESCE(SUM(di.quantity * oi.unit_price), 0)
                FROM sales_delivery_item di
                JOIN sales_delivery d ON d.id = di.delivery_id AND d.status IN ('COMPLETED', 'ACTIVE')
                JOIN sales_order_item oi ON oi.order_id = d.order_id AND oi.product_id = di.product_id
                WHERE d.delivery_date = :today
                """, params, BigDecimal.class);

        return new DashboardVO(
                pending == null ? 0 : pending, "/approval/pending",
                nearExpiry == null ? 0 : nearExpiry, "/reports/near-expiry",
                licenseExpiring == null ? 0 : licenseExpiring, "/base/customers?licenseExpiring=1",
                todayPurchase == null ? BigDecimal.ZERO : todayPurchase, "/purchase/receives",
                todaySales == null ? BigDecimal.ZERO : todaySales, "/sales/deliveries",
                trends(today));
    }

    /** 近 7 天趋势：每日入库金额（入库成本合计）/ 出库金额（订单单价合计），缺日补 0。 */
    private List<TrendPointVO> trends(LocalDate today) {
        LocalDate from = today.minusDays(6);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", from)
                .addValue("to", today);

        Map<LocalDate, BigDecimal> purchase = new java.util.HashMap<>();
        jdbc.query("""
                SELECT r.receive_date AS d, COALESCE(SUM(ri.quantity * ri.unit_cost), 0) AS amt
                FROM purchase_receive_item ri
                JOIN purchase_receive r ON r.id = ri.receive_id AND r.status IN ('COMPLETED', 'ACTIVE')
                WHERE r.receive_date BETWEEN :from AND :to
                GROUP BY r.receive_date
                """, params, rs -> {
            purchase.put(rs.getDate("d").toLocalDate(), rs.getBigDecimal("amt"));
        });

        Map<LocalDate, BigDecimal> sales = new java.util.HashMap<>();
        jdbc.query("""
                SELECT d.delivery_date AS d, COALESCE(SUM(di.quantity * oi.unit_price), 0) AS amt
                FROM sales_delivery_item di
                JOIN sales_delivery d ON d.id = di.delivery_id AND d.status IN ('COMPLETED', 'ACTIVE')
                JOIN sales_order_item oi ON oi.order_id = d.order_id AND oi.product_id = di.product_id
                WHERE d.delivery_date BETWEEN :from AND :to
                GROUP BY d.delivery_date
                """, params, rs -> {
            sales.put(rs.getDate("d").toLocalDate(), rs.getBigDecimal("amt"));
        });

        List<TrendPointVO> points = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = from.plusDays(i);
            points.add(new TrendPointVO(day,
                    purchase.getOrDefault(day, BigDecimal.ZERO),
                    sales.getOrDefault(day, BigDecimal.ZERO)));
        }
        return points;
    }
}
