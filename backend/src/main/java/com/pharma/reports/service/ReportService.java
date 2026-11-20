package com.pharma.reports.service;

import com.pharma.approval.dto.ApprovalPendingVO;
import com.pharma.approval.service.ApprovalService;
import com.pharma.common.DataScope;
import com.pharma.common.PageResult;
import com.pharma.reports.dto.DashboardNearExpiryVO;
import com.pharma.reports.dto.DashboardTodoVO;
import com.pharma.reports.dto.DashboardTopProductVO;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 基础报表服务：进销存汇总 / 近效期清单 / 库存余额 / 毛利 / 工作台聚合。
 * 基于 stock_transaction / stock 直接 SQL 聚合，与 append-only 流水一致。
 * 过滤条件在 Java 侧动态拼接，避免 NULL 命名参数的类型推断问题。
 * 工作台待办复用审批服务（ApprovalService 不反依赖报表模块，不存在循环依赖）。
 */
@Service
public class ReportService {

    private static final int DEFAULT_NEAR_EXPIRY_DAYS = 30;

    /** 工作台待办条数上限（与前端「待办事项」面板展示容量一致）。 */
    private static final int DASHBOARD_TODO_LIMIT = 5;

    /** 工作台近效期预警条数上限。 */
    private static final int DASHBOARD_NEAR_EXPIRY_LIMIT = 8;

    /** 工作台本月销售排行条数上限。 */
    private static final int DASHBOARD_TOP_PRODUCT_LIMIT = 5;

    /** 待办展示用的单据类型中文名（与审批列表、前端 PendingList 口径一致）。 */
    private static final Map<String, String> BIZ_TYPE_LABELS = Map.of(
            ApprovalService.BIZ_PURCHASE_ORDER, "采购订单",
            ApprovalService.BIZ_SALES_ORDER, "销售订单",
            ApprovalService.BIZ_SALES_RETURN, "销售退货",
            ApprovalService.BIZ_PURCHASE_RECEIVE, "采购入库",
            ApprovalService.BIZ_SALES_DELIVERY, "销售出库",
            ApprovalService.BIZ_PURCHASE_RETURN, "采购退货",
            ApprovalService.BIZ_STOCK_TRANSFER, "仓库调拨");

    /** 报表 JDBC 模板：所有报表以只读 SQL 聚合。 */
    private final NamedParameterJdbcTemplate jdbc;

    /** 审批服务：工作台待办复用待审列表（含审批角色过滤与往来单位解析）。 */
    private final ApprovalService approvalService;

    public ReportService(NamedParameterJdbcTemplate jdbc, ApprovalService approvalService) {
        this.jdbc = jdbc;
        this.approvalService = approvalService;
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

    /**
     * 工作台 Dashboard（P1-29）：卡片数字 + 跳转路径 + 近 7 天趋势，
     * 并一次聚合待办 / 近效期 / 本月销售排行，避免前端逐个调用降级接口。
     * 待办仅对具备 approval:view 的用户下发，其余用户返回空列表（不越过审批数据权限）。
     */
    public DashboardVO dashboard() {
        // 1. 统一日期参数：近效期 30 天、证照到期 60 天、今日金额
        LocalDate today = LocalDate.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("expireBefore", today.plusDays(DEFAULT_NEAR_EXPIRY_DAYS))
                .addValue("licenseBefore", today.plusDays(60))
                .addValue("today", today);

        // 2. 待审批 / 近效期计数
        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_record WHERE status = 'PENDING'", params, Long.class);
        Long nearExpiry = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock WHERE quantity > 0 AND expire_date <= :expireBefore",
                params, Long.class);
        // 3. 证照 60 天内到期计数：客户 + 供应商 + 门店（任一非空即计入）
        Long licenseExpiring = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM customer WHERE status = 'ACTIVE'
                          AND license_expire IS NOT NULL AND license_expire <= :licenseBefore)
                     + (SELECT COUNT(*) FROM supplier WHERE status = 'ACTIVE'
                          AND license_expire IS NOT NULL AND license_expire <= :licenseBefore)
                     + (SELECT COUNT(*) FROM store WHERE status = 'ACTIVE'
                          AND license_expire IS NOT NULL AND license_expire <= :licenseBefore)
                """, params, Long.class);

        // 4. 今日金额：执行态口径为 COMPLETED（历史数据兼容 ACTIVE）
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

        // 5. 明细聚合：待办 / 近效期 / 本月销售排行
        List<DashboardTodoVO> dashboardTodoList = dashboardTodos();
        List<DashboardNearExpiryVO> dashboardNearExpiryList = dashboardNearExpiry();
        List<DashboardTopProductVO> dashboardTopProductList = dashboardTopProducts(today);

        // 6. 组装返回：原有字段顺序与语义不变，新增明细追加在末尾
        return new DashboardVO(
                pending == null ? 0 : pending, "/approval/pending",
                nearExpiry == null ? 0 : nearExpiry, "/reports/near-expiry",
                licenseExpiring == null ? 0 : licenseExpiring, "/base/customers?licenseExpiring=1",
                todayPurchase == null ? BigDecimal.ZERO : todayPurchase, "/purchase/receives",
                todaySales == null ? BigDecimal.ZERO : todaySales, "/sales/deliveries",
                trends(today), dashboardTodoList, dashboardNearExpiryList, dashboardTopProductList);
    }

    /**
     * 工作台待办：仅对具备 approval:view 的用户查询前 5 条待审批单据。
     * 权限不足时直接返回空列表，避免把审批数据泄露给普通报表用户。
     *
     * @return 待办列表（按待审列表默认排序），无权限或无待办时为空列表
     */
    private List<DashboardTodoVO> dashboardTodos() {
        // 1. 权限校验：待办属于审批数据，没有 approval:view 不下发
        if (!DataScope.hasAuthority(DataScope.APPROVAL_VIEW)) {
            return List.of();
        }
        // 2. 复用待审列表第 1 页前 5 条，映射为工作台待办字段
        PageResult<ApprovalPendingVO> pendingPage = approvalService.pending(1, DASHBOARD_TODO_LIMIT);
        List<ApprovalPendingVO> pendingList = pendingPage.getRecords() == null
                ? List.of() : pendingPage.getRecords();
        return pendingList.stream()
                .map(pending -> new DashboardTodoVO(pending.bizType(), pending.bizId(),
                        todoTitle(pending), pending.orderDate()))
                .toList();
    }

    /**
     * 拼接待办标题：单据类型中文 + 单号 + 往来单位，缺失片段自动省略。
     *
     * @param pending 待审批单据统一视图
     * @return 形如「采购订单 PO-001 · 某供应商」的待办标题
     */
    private String todoTitle(ApprovalPendingVO pending) {
        // 1. 单据类型中文名打底，未知类型回退原始编码
        String bizTypeLabel = BIZ_TYPE_LABELS.getOrDefault(pending.bizType(), pending.bizType());
        StringBuilder title = new StringBuilder(bizTypeLabel == null ? "" : bizTypeLabel);
        // 2. 依次追加单号与往来单位，缺失片段直接省略
        if (pending.orderNo() != null && !pending.orderNo().isBlank()) {
            title.append(' ').append(pending.orderNo());
        }
        if (pending.partyName() != null && !pending.partyName().isBlank()) {
            title.append(" · ").append(pending.partyName());
        }
        return title.toString().trim();
    }

    /**
     * 工作台近效期预警：复用近效期清单（30 天）按效期升序取前 8 条。
     *
     * @return 近效期行，商品名由通用名/商品名/规格拼接
     */
    private List<DashboardNearExpiryVO> dashboardNearExpiry() {
        // 1. 复用 30 天近效期清单（已按效期升序）
        // 2. 截断到前 8 条并映射为工作台精简字段
        return nearExpiryList(DEFAULT_NEAR_EXPIRY_DAYS).stream()
                .limit(DASHBOARD_NEAR_EXPIRY_LIMIT)
                .map(item -> new DashboardNearExpiryVO(item.productId(),
                        displayProductName(item.genericName(), item.tradeName(), item.spec()),
                        item.batchNo(), item.expireDate(), item.quantity(), item.daysLeft()))
                .toList();
    }

    /**
     * 拼接商品展示名：通用名（商品名）+ 规格，缺失片段自动省略。
     *
     * @param genericName 通用名，可空
     * @param tradeName   商品名，可空；与通用名相同或为空时不重复展示
     * @param spec        规格，可空
     * @return 展示名，如「阿莫西林胶囊（阿莫仙） 0.25g*24 粒」
     */
    private String displayProductName(String genericName, String tradeName, String spec) {
        // 1. 统一空值处理，避免拼接出 null
        String generic = genericName == null ? "" : genericName.trim();
        String trade = tradeName == null ? "" : tradeName.trim();
        String specText = spec == null ? "" : spec.trim();
        // 2. 通用名打底，再追加去重后的商品名与规格
        StringBuilder name = new StringBuilder(generic);
        // WHY: 商品名与通用名相同时（演示数据常见）不重复展示，避免「XX（XX）」
        if (!trade.isBlank() && !trade.equals(generic)) {
            if (name.length() > 0) {
                name.append("（").append(trade).append("）");
            } else {
                name.append(trade);
            }
        }
        if (!specText.isBlank()) {
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(specText);
        }
        return name.toString().trim();
    }

    /**
     * 工作台本月商品销售排行：复用毛利报表（本月 1 日 ~ 今日）按销售金额降序取前 5。
     *
     * @param today 统计截止日（含）
     * @return 排行行，amount 取毛利报表的 revenue（销售额，未扣成本）
     */
    private List<DashboardTopProductVO> dashboardTopProducts(LocalDate today) {
        // 1. 统计区间：本月 1 日至今日
        LocalDate monthStart = today.withDayOfMonth(1);
        // 2. 取正收入商品按金额降序截断前 5，映射排行字段（amount = revenue）
        return grossProfit(null, monthStart, today).stream()
                .filter(row -> row.revenue() != null && row.revenue().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(GrossProfitVO::revenue).reversed())
                .limit(DASHBOARD_TOP_PRODUCT_LIMIT)
                .map(row -> new DashboardTopProductVO(row.productId(),
                        row.productName() == null ? row.productCode() : row.productName(),
                        row.quantity(), row.revenue()))
                .toList();
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
