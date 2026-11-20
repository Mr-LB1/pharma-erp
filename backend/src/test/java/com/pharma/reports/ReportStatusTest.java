package com.pharma.reports;

import com.pharma.inventory.AbstractInventoryTest;
import com.pharma.reports.dto.DashboardVO;
import com.pharma.reports.dto.GrossProfitVO;
import com.pharma.reports.dto.TrendPointVO;
import com.pharma.reports.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报表执行态口径回归（P1-C）：批次十四单据终态为 COMPLETED（历史 V26 归一前为 ACTIVE），
 * 毛利、工作台今日采购/销售、近 7 天趋势必须按 COMPLETED + ACTIVE 并集统计，否则新单据报表恒为 0。
 */
class ReportStatusTest extends AbstractInventoryTest {

    /** 报表服务：被测对象，覆盖毛利 / 工作台今日金额 / 近 7 天趋势。 */
    @Autowired
    private ReportService reportService;

    /** JDBC 模板：按 V4/V24 表结构直接造 COMPLETED / ACTIVE 执行单据，聚焦状态过滤口径。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 用例：COMPLETED 入库/出库单据应进入毛利、今日采购金额、今日销售金额与近 7 天趋势；
     * 未执行状态（PUSHED / SUBMITTED）单据不得计入。
     */
    @Test
    void COMPLETED入库出库计入毛利今日金额与趋势() {
        // 1. 基线采样：共享库可能已有演示数据，金额与趋势按差值断言，避免历史数据干扰
        LocalDate today = LocalDate.now();
        DashboardVO baselineDashboard = reportService.dashboard();
        TrendPointVO baselineTodayTrend = findTrendPoint(baselineDashboard, today);

        // 2. 造 COMPLETED 采购入库：5 件 × 成本 8 = 今日采购 40
        String completedBatchNo = "RPT-C-" + System.nanoTime();
        Long completedPurchaseOrderId = insertPurchaseOrder("PO-C-" + System.nanoTime(), today);
        Long completedReceiveId = insertReceive("PR-C-" + System.nanoTime(), completedPurchaseOrderId,
                today, "COMPLETED");
        insertReceiveItem(completedReceiveId, completedBatchNo, new BigDecimal("5"),
                new BigDecimal("8"), today.plusDays(180));

        // 3. 造 COMPLETED 销售出库：3 件 × 订单单价 20 = 收入 60，结转成本 3 × 8 = 24，毛利 36
        Long completedSalesOrderId = insertSalesOrder("SO-C-" + System.nanoTime(), today);
        insertSalesOrderItem(completedSalesOrderId, new BigDecimal("3"), new BigDecimal("20"));
        Long completedDeliveryId = insertDelivery("SD-C-" + System.nanoTime(), completedSalesOrderId,
                today, "COMPLETED");
        insertDeliveryItem(completedDeliveryId, completedBatchNo, new BigDecimal("3"), new BigDecimal("8"));

        // 4. 造未执行噪声单据（金额放大到 100 × 100），状态过滤口径错误时断言会同时暴露
        Long pushedPurchaseOrderId = insertPurchaseOrder("PO-N-" + System.nanoTime(), today);
        Long pushedReceiveId = insertReceive("PR-N-" + System.nanoTime(), pushedPurchaseOrderId,
                today, "PUSHED");
        insertReceiveItem(pushedReceiveId, "RPT-N-" + System.nanoTime(), new BigDecimal("100"),
                new BigDecimal("100"), today.plusDays(180));
        Long submittedSalesOrderId = insertSalesOrder("SO-N-" + System.nanoTime(), today);
        insertSalesOrderItem(submittedSalesOrderId, new BigDecimal("100"), new BigDecimal("100"));
        Long submittedDeliveryId = insertDelivery("SD-N-" + System.nanoTime(), submittedSalesOrderId,
                today, "SUBMITTED");
        insertDeliveryItem(submittedDeliveryId, "RPT-N-" + System.nanoTime(), new BigDecimal("100"),
                new BigDecimal("100"));

        // 5. 毛利：COMPLETED 出库单进入统计，未执行单据被排除
        List<GrossProfitVO> grossProfitRowList = reportService.grossProfit(productId, today, today);
        GrossProfitVO completedGrossProfitRow = grossProfitRowList.stream()
                .filter(row -> productId.equals(row.productId()))
                .findFirst()
                .orElseThrow();
        assertThat(completedGrossProfitRow.quantity()).isEqualByComparingTo("3");
        assertThat(completedGrossProfitRow.revenue()).isEqualByComparingTo("60");
        assertThat(completedGrossProfitRow.cost()).isEqualByComparingTo("24");
        assertThat(completedGrossProfitRow.grossProfit()).isEqualByComparingTo("36");

        // 6. 今日金额与趋势：COMPLETED 单据按差值进入今日采购 40 / 今日销售 60
        DashboardVO afterDashboard = reportService.dashboard();
        TrendPointVO afterTodayTrend = findTrendPoint(afterDashboard, today);
        assertThat(afterDashboard.todayPurchaseAmount().subtract(baselineDashboard.todayPurchaseAmount()))
                .isEqualByComparingTo("40");
        assertThat(afterDashboard.todaySalesAmount().subtract(baselineDashboard.todaySalesAmount()))
                .isEqualByComparingTo("60");
        assertThat(afterTodayTrend.inboundAmount().subtract(baselineTodayTrend.inboundAmount()))
                .isEqualByComparingTo("40");
        assertThat(afterTodayTrend.outboundAmount().subtract(baselineTodayTrend.outboundAmount()))
                .isEqualByComparingTo("60");
        assertThat(afterDashboard.trends()).hasSize(7);
    }

    /**
     * 用例：历史 ACTIVE 单据（V26 归一前的旧执行态）仍应保留在毛利、今日金额与趋势口径内，避免修复后丢掉存量数据。
     */
    @Test
    void 历史ACTIVE入库出库仍计入报表() {
        // 1. 基线采样：同 COMPLETED 用例，按差值断言避免共享库历史数据干扰
        LocalDate today = LocalDate.now();
        DashboardVO baselineDashboard = reportService.dashboard();
        TrendPointVO baselineTodayTrend = findTrendPoint(baselineDashboard, today);

        // 2. 造历史 ACTIVE 采购入库：2 件 × 成本 10 = 今日采购 20
        String activeBatchNo = "RPT-A-" + System.nanoTime();
        Long activePurchaseOrderId = insertPurchaseOrder("PO-A-" + System.nanoTime(), today);
        Long activeReceiveId = insertReceive("PR-A-" + System.nanoTime(), activePurchaseOrderId,
                today, "ACTIVE");
        insertReceiveItem(activeReceiveId, activeBatchNo, new BigDecimal("2"),
                new BigDecimal("10"), today.plusDays(180));

        // 3. 造历史 ACTIVE 销售出库：1 件 × 订单单价 30 = 收入 30，结转成本 10，毛利 20
        Long activeSalesOrderId = insertSalesOrder("SO-A-" + System.nanoTime(), today);
        insertSalesOrderItem(activeSalesOrderId, new BigDecimal("1"), new BigDecimal("30"));
        Long activeDeliveryId = insertDelivery("SD-A-" + System.nanoTime(), activeSalesOrderId,
                today, "ACTIVE");
        insertDeliveryItem(activeDeliveryId, activeBatchNo, new BigDecimal("1"), new BigDecimal("10"));

        // 4. 毛利：历史 ACTIVE 出库单仍进入统计
        List<GrossProfitVO> grossProfitRowList = reportService.grossProfit(productId, today, today);
        GrossProfitVO activeGrossProfitRow = grossProfitRowList.stream()
                .filter(row -> productId.equals(row.productId()))
                .findFirst()
                .orElseThrow();
        assertThat(activeGrossProfitRow.quantity()).isEqualByComparingTo("1");
        assertThat(activeGrossProfitRow.revenue()).isEqualByComparingTo("30");
        assertThat(activeGrossProfitRow.cost()).isEqualByComparingTo("10");
        assertThat(activeGrossProfitRow.grossProfit()).isEqualByComparingTo("20");

        // 5. 今日金额与趋势：历史 ACTIVE 单据按差值进入今日采购 20 / 今日销售 30
        DashboardVO afterDashboard = reportService.dashboard();
        TrendPointVO afterTodayTrend = findTrendPoint(afterDashboard, today);
        assertThat(afterDashboard.todayPurchaseAmount().subtract(baselineDashboard.todayPurchaseAmount()))
                .isEqualByComparingTo("20");
        assertThat(afterDashboard.todaySalesAmount().subtract(baselineDashboard.todaySalesAmount()))
                .isEqualByComparingTo("30");
        assertThat(afterTodayTrend.inboundAmount().subtract(baselineTodayTrend.inboundAmount()))
                .isEqualByComparingTo("20");
        assertThat(afterTodayTrend.outboundAmount().subtract(baselineTodayTrend.outboundAmount()))
                .isEqualByComparingTo("30");
    }

    /**
     * 在近 7 天趋势中定位指定日期的趋势点。
     *
     * @param dashboard 工作台数据，包含今日往前 7 天的趋势点
     * @param day       目标日期，必须落在近 7 天范围内
     * @return 该日期的趋势点，含当日入库金额与出库金额
     */
    private TrendPointVO findTrendPoint(DashboardVO dashboard, LocalDate day) {
        return dashboard.trends().stream()
                .filter(point -> day.equals(point.date()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 插入采购订单主表（报表只依赖主键与单据日期，状态置 COMPLETED 表示订单已执行完毕）。
     *
     * @param orderNo   采购订单号，全局唯一
     * @param orderDate 采购订单日期
     * @return 新采购订单主键
     */
    private Long insertPurchaseOrder(String orderNo, LocalDate orderDate) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO purchase_order (order_no, supplier_id, order_date, status)
                VALUES (?, ?, ?, 'COMPLETED')
                RETURNING id
                """, Long.class, orderNo, supplierId, orderDate);
    }

    /**
     * 插入采购入库单主表。
     *
     * @param receiveNo   入库单号，全局唯一
     * @param orderId     关联采购订单主键
     * @param receiveDate 入库日期（报表按该日期归集）
     * @param status      入库单状态：COMPLETED 为批次十四终态，ACTIVE 为历史执行态
     * @return 新采购入库单主键
     */
    private Long insertReceive(String receiveNo, Long orderId, LocalDate receiveDate, String status) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO purchase_receive (receive_no, order_id, receive_date, status)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, receiveNo, orderId, receiveDate, status);
    }

    /**
     * 插入采购入库明细（报表入库金额 = 数量 × 单位成本）。
     *
     * @param receiveId  入库单主键
     * @param batchNo    批号
     * @param quantity   入库数量
     * @param unitCost   入库单位成本
     * @param expireDate 批次效期
     */
    private void insertReceiveItem(Long receiveId, String batchNo, BigDecimal quantity,
                                   BigDecimal unitCost, LocalDate expireDate) {
        jdbcTemplate.update("""
                INSERT INTO purchase_receive_item (receive_id, product_id, quantity, batch_no, expire_date, unit_cost)
                VALUES (?, ?, ?, ?, ?, ?)
                """, receiveId, productId, quantity, batchNo, expireDate, unitCost);
    }

    /**
     * 插入销售订单主表（毛利报表按 order_id + product_id 关联订单单价）。
     *
     * @param orderNo   销售订单号，全局唯一
     * @param orderDate 销售订单日期
     * @return 新销售订单主键
     */
    private Long insertSalesOrder(String orderNo, LocalDate orderDate) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO sales_order (order_no, customer_id, order_date, status)
                VALUES (?, ?, ?, 'COMPLETED')
                RETURNING id
                """, Long.class, orderNo, customerId, orderDate);
    }

    /**
     * 插入销售订单明细（毛利报表取 unit_price 作为销售收入单价）。
     *
     * @param orderId   销售订单主键
     * @param quantity  订单数量
     * @param unitPrice 订单单价
     */
    private void insertSalesOrderItem(Long orderId, BigDecimal quantity, BigDecimal unitPrice) {
        jdbcTemplate.update("""
                INSERT INTO sales_order_item (order_id, product_id, quantity, unit_price, amount)
                VALUES (?, ?, ?, ?, ?)
                """, orderId, productId, quantity, unitPrice, quantity.multiply(unitPrice));
    }

    /**
     * 插入销售出库单主表。
     *
     * @param deliveryNo   出库单号，全局唯一
     * @param orderId      关联销售订单主键
     * @param deliveryDate 出库日期（报表按该日期归集）
     * @param status       出库单状态：COMPLETED 为批次十四终态，ACTIVE 为历史执行态
     * @return 新销售出库单主键
     */
    private Long insertDelivery(String deliveryNo, Long orderId, LocalDate deliveryDate, String status) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO sales_delivery (delivery_no, order_id, delivery_date, status)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, deliveryNo, orderId, deliveryDate, status);
    }

    /**
     * 插入销售出库明细（毛利报表成本 = 数量 × 结转单位成本）。
     *
     * @param deliveryId 出库单主键
     * @param batchNo    批号
     * @param quantity   出库数量
     * @param unitCost   出库结转单位成本
     */
    private void insertDeliveryItem(Long deliveryId, String batchNo, BigDecimal quantity, BigDecimal unitCost) {
        jdbcTemplate.update("""
                INSERT INTO sales_delivery_item (delivery_id, product_id, batch_no, quantity, unit_cost)
                VALUES (?, ?, ?, ?, ?)
                """, deliveryId, productId, batchNo, quantity, unitCost);
    }
}
