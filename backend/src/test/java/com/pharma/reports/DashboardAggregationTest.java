package com.pharma.reports;

import com.pharma.base.entity.Product;
import com.pharma.inventory.AbstractInventoryTest;
import com.pharma.reports.dto.DashboardNearExpiryVO;
import com.pharma.reports.dto.DashboardTodoVO;
import com.pharma.reports.dto.DashboardTopProductVO;
import com.pharma.reports.dto.DashboardVO;
import com.pharma.reports.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作台 Dashboard 聚合回归（优化3）：一次返回待办 / 近效期 / 本月销售排行。
 * 待办受 approval:view 数据权限约束；近效期复用 30 天清单取前 8 条；排行复用毛利报表取前 5 条。
 * 数据按 V4/V5/V10 表结构直插，聚焦字段映射与权限口径，避免依赖完整单据流转。
 */
class DashboardAggregationTest extends AbstractInventoryTest {

    /** 报表服务：被测对象，覆盖 dashboard 的卡片、趋势与三组明细聚合。 */
    @Autowired
    private ReportService reportService;

    /** JDBC 模板：直插待审单据/销售出库/门店等报表依赖数据，避开 JPA 未 flush 的读写时序。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 跨域允许来源配置：验证默认值已包含 127.0.0.1 前端地址。 */
    @Value("${app.cors.allowed-origins}")
    private String corsAllowedOrigins;

    /**
     * 用例：工作台一次返回三组明细且字段严格按前端契约映射。
     * 待办 bizType/bizId/title/createdAt；近效期 productId/productName/batchNo/expireDate/quantity/daysLeft；
     * 排行 productId/productName/quantity/amount（amount 取 sales revenue）。
     */
    @Test
    void 工作台一次返回待办近效期与销售排行且字段映射正确() {
        // 1. 准备三类明细数据：待审采购订单 / 近效期批次 / 本月已完成销售出库
        LocalDate today = LocalDate.now();
        String purchaseOrderNo = "PO-DASH-" + System.nanoTime();
        Long purchaseOrderId = insertSubmittedPurchaseOrder(purchaseOrderNo, today);
        String nearExpiryBatchNo = "DASH-NEAR-" + System.nanoTime();
        createStock(nearExpiryBatchNo, today.plusDays(10), new BigDecimal("12"), new BigDecimal("1.5"));
        String deliveryNo = "SD-DASH-" + System.nanoTime();
        insertSalesData("SO-" + deliveryNo, deliveryNo, today,
                new BigDecimal("3"), new BigDecimal("20"), "COMPLETED");

        // 2. 以 ADMIN + approval:view 身份调用工作台（待办仅对审批查看权限下发）
        DashboardVO dashboard = callAs("admin", List.of("ROLE_ADMIN", "approval:view"),
                () -> reportService.dashboard());

        // 3. 待办：bizType/bizId/title/createdAt 与待审单据一致，条数不超过 5
        assertThat(dashboard.todos()).isNotNull().hasSizeBetween(1, 5);
        DashboardTodoVO todo = findTodo(dashboard.todos(), purchaseOrderId);
        assertThat(todo.bizType()).isEqualTo("PURCHASE_ORDER");
        assertThat(todo.bizId()).isEqualTo(purchaseOrderId);
        assertThat(todo.title()).contains("采购订单").contains(purchaseOrderNo).contains("测试供应商");
        assertThat(todo.createdAt()).isEqualTo(today);

        // 4. 近效期：条数不超过 8，六个字段均为前端可用类型与值
        assertThat(dashboard.nearExpiry()).isNotNull().hasSizeBetween(1, 8);
        DashboardNearExpiryVO nearExpiry = findNearExpiry(dashboard.nearExpiry(), nearExpiryBatchNo);
        assertThat(nearExpiry.productId()).isEqualTo(productId);
        assertThat(nearExpiry.productName()).isEqualTo("测试药品");
        assertThat(nearExpiry.batchNo()).isEqualTo(nearExpiryBatchNo);
        assertThat(nearExpiry.expireDate()).isEqualTo(today.plusDays(10));
        assertThat(nearExpiry.quantity()).isEqualByComparingTo("12");
        assertThat(nearExpiry.daysLeft()).isEqualTo(10);

        // 5. 销售排行：条数不超过 5，amount 与毛利报表 revenue 一致
        assertThat(dashboard.topProducts()).isNotNull().hasSizeBetween(1, 5);
        DashboardTopProductVO topProduct = findTopProduct(dashboard.topProducts(), productId);
        assertThat(topProduct.productName()).isEqualTo("测试药品");
        assertThat(topProduct.quantity()).isEqualByComparingTo("3");
        assertThat(topProduct.amount()).isEqualByComparingTo("60");

        // 6. 原有卡片字段保持兼容：趋势仍为 7 天，今日金额非空
        assertThat(dashboard.trends()).hasSize(7);
        assertThat(dashboard.todayPurchaseAmount()).isNotNull();
        assertThat(dashboard.todaySalesAmount()).isNotNull();
    }

    /**
     * 用例：无 approval:view 权限时 todos 返回空列表，具备权限时能拿到刚提交的待审单据。
     * 证明待办聚合受数据权限控制，而不是对所有 report:view 用户开放。
     */
    @Test
    void 无approvalview权限时待办为空() {
        // 1. 造一张 SUBMITTED 采购订单作为待办数据
        LocalDate today = LocalDate.now();
        String purchaseOrderNo = "PO-PERM-" + System.nanoTime();
        Long purchaseOrderId = insertSubmittedPurchaseOrder(purchaseOrderNo, today);

        // 2. 仅 report:view：待办必须为空列表
        DashboardVO reporterDashboard = callAs("reporter", List.of("report:view"),
                () -> reportService.dashboard());
        assertThat(reporterDashboard.todos()).isEmpty();

        // 3. ADMIN + approval:view：同一张订单应出现在待办中（对照证明差异来自权限）
        DashboardVO adminDashboard = callAs("admin", List.of("ROLE_ADMIN", "approval:view"),
                () -> reportService.dashboard());
        assertThat(findTodo(adminDashboard.todos(), purchaseOrderId).bizType()).isEqualTo("PURCHASE_ORDER");
    }

    /**
     * 用例：COMPLETED 销售出库进入本月销售排行，未执行状态（PUSHED）不得计入金额。
     * 排行金额取毛利报表 revenue，状态过滤口径需与毛利报表一致。
     */
    @Test
    void COMPLETED销售出库进入本月销售排行() {
        // 1. 造 COMPLETED 出库：3 × 9,999,999 = 29,999,997（本月）
        LocalDate today = LocalDate.now();
        String completedDeliveryNo = "SD-RANK-C-" + System.nanoTime();
        insertSalesData("SO-" + completedDeliveryNo, completedDeliveryNo, today,
                new BigDecimal("3"), new BigDecimal("9999999"), "COMPLETED");

        // 2. 造 PUSHED 噪声：金额放大到 999,999,990，状态过滤错误会同时暴露
        String pushedDeliveryNo = "SD-RANK-N-" + System.nanoTime();
        insertSalesData("SO-" + pushedDeliveryNo, pushedDeliveryNo, today,
                new BigDecimal("10"), new BigDecimal("99999999"), "PUSHED");

        // 3. 排行：产品数量/金额只统计 COMPLETED，PUSHED 噪声被排除
        DashboardVO dashboard = reportService.dashboard();
        assertThat(dashboard.topProducts()).isNotNull().hasSizeBetween(1, 5);
        DashboardTopProductVO topProduct = findTopProduct(dashboard.topProducts(), productId);
        assertThat(topProduct.quantity()).isEqualByComparingTo("3");
        assertThat(topProduct.amount()).isEqualByComparingTo("29999997");
    }

    /**
     * 用例：近效期明细按效期升序取前 8 条，productName 由通用名（商品名）+ 规格拼接。
     */
    @Test
    void 近效期按效期升序取前8条且商品名拼接() {
        // 1. 补全商品名/规格，验证 productName 拼接口径
        LocalDate today = LocalDate.now();
        Product product = productRepository.findById(productId).orElseThrow();
        product.setTradeName("测试商品名");
        product.setSpec("0.5g*10");
        productRepository.saveAndFlush(product);

        // 2. 造 9 个近效期批次（今日 +1 ~ +9），验证截断到前 8 条
        String batchPrefix = "DASH-NEAR-" + System.nanoTime() + "-";
        for (int i = 1; i <= 9; i++) {
            createStock(batchPrefix + i, today.plusDays(i), BigDecimal.ONE, BigDecimal.ONE);
        }
        DashboardVO dashboard = reportService.dashboard();
        List<DashboardNearExpiryVO> nearExpiryList = dashboard.nearExpiry();

        // 3. 排序与截断：首条为最早到期批次，第 9 个批次被截断
        assertThat(nearExpiryList).hasSize(8);
        assertThat(nearExpiryList).extracting(DashboardNearExpiryVO::expireDate).isSorted();
        assertThat(nearExpiryList.get(0).batchNo()).isEqualTo(batchPrefix + "1");
        assertThat(nearExpiryList).noneMatch(item -> (batchPrefix + "9").equals(item.batchNo()));

        // 4. 字段映射：productName = 通用名（商品名）+ 规格，剩余天数按效期计算
        DashboardNearExpiryVO firstRow = nearExpiryList.get(0);
        assertThat(firstRow.productId()).isEqualTo(productId);
        assertThat(firstRow.productName()).isEqualTo("测试药品（测试商品名） 0.5g*10");
        assertThat(firstRow.expireDate()).isEqualTo(today.plusDays(1));
        assertThat(firstRow.daysLeft()).isEqualTo(1);
        assertThat(firstRow.quantity()).isEqualByComparingTo("1");
    }

    /**
     * 用例：证照到期计数在客户 + 供应商基础上前回门店表（ACTIVE 且 60 天内到期）。
     */
    @Test
    void 证照到期计数包含门店许可证() {
        // 1. 基线采样：共享库可能已有客户/供应商证照数据，按差值断言
        long baselineCount = reportService.dashboard().licenseExpiringCount();

        // 2. 造门店数据：ACTIVE 30 天内到期计入；90 天后到期与非 ACTIVE 不计入
        String suffix = String.valueOf(System.nanoTime());
        insertStore("A-" + suffix, LocalDate.now().plusDays(30), "ACTIVE");
        insertStore("B-" + suffix, LocalDate.now().plusDays(90), "ACTIVE");
        insertStore("C-" + suffix, LocalDate.now().plusDays(30), "INACTIVE");

        // 3. 断言仅 ACTIVE 且 60 天内到期的门店增加 1
        DashboardVO dashboard = reportService.dashboard();
        assertThat(dashboard.licenseExpiringCount()).isEqualTo(baselineCount + 1);
    }

    /**
     * 用例：CORS 默认来源包含 127.0.0.1:5173（本机前端通过 IP 访问时不被跨域拦截）。
     */
    @Test
    void CORS默认来源包含127001前端地址() {
        assertThat(corsAllowedOrigins).contains("http://localhost:5173");
        assertThat(corsAllowedOrigins).contains("http://127.0.0.1:5173");
    }

    /**
     * 插入一张 SUBMITTED 采购订单作为工作台待办数据。
     *
     * @param orderNo   采购订单号，全局唯一
     * @param orderDate 订单业务日期，映射为待办 createdAt
     * @return 新采购订单主键
     */
    private Long insertSubmittedPurchaseOrder(String orderNo, LocalDate orderDate) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO purchase_order (order_no, supplier_id, order_date, status, created_by)
                VALUES (?, ?, ?, 'SUBMITTED', 'creator')
                RETURNING id
                """, Long.class, orderNo, supplierId, orderDate);
    }

    /**
     * 插入一张销售出库单及其销售订单明细（工作台排行按销售订单单价计算 revenue）。
     *
     * @param orderNo        销售订单号，全局唯一
     * @param deliveryNo     出库单号，全局唯一
     * @param deliveryDate   出库日期（本月经统计范围内）
     * @param quantity       出库数量
     * @param unitPrice      销售订单单价
     * @param deliveryStatus 出库单状态：COMPLETED 为执行态，PUSHED 为未执行噪声
     */
    private void insertSalesData(String orderNo, String deliveryNo, LocalDate deliveryDate,
                                 BigDecimal quantity, BigDecimal unitPrice, String deliveryStatus) {
        Long orderId = jdbcTemplate.queryForObject("""
                INSERT INTO sales_order (order_no, customer_id, order_date, status)
                VALUES (?, ?, ?, 'COMPLETED')
                RETURNING id
                """, Long.class, orderNo, customerId, deliveryDate);
        jdbcTemplate.update("""
                INSERT INTO sales_order_item (order_id, product_id, quantity, unit_price, amount)
                VALUES (?, ?, ?, ?, ?)
                """, orderId, productId, quantity, unitPrice, quantity.multiply(unitPrice));
        Long deliveryId = jdbcTemplate.queryForObject("""
                INSERT INTO sales_delivery (delivery_no, order_id, delivery_date, status)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, deliveryNo, orderId, deliveryDate, deliveryStatus);
        jdbcTemplate.update("""
                INSERT INTO sales_delivery_item (delivery_id, product_id, batch_no, quantity, unit_cost)
                VALUES (?, ?, ?, ?, ?)
                """, deliveryId, productId, "RANK-" + deliveryNo, quantity, BigDecimal.ONE);
    }

    /**
     * 插入一条门店主数据（证照到期计数来源之一）。
     *
     * @param code          门店编码后缀，全局唯一
     * @param licenseExpire 许可证到期日
     * @param status        门店状态：ACTIVE 才计入证照到期统计
     * @return 新门店主键
     */
    private Long insertStore(String code, LocalDate licenseExpire, String status) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO store (code, name, warehouse_id, license_expire, status)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, "S-" + code, "测试门店" + code, warehouseId, licenseExpire, status);
    }

    /**
     * 在待办列表中定位指定单据主键的待办行。
     *
     * @param todoList 工作台待办列表
     * @param bizId    单据主键
     * @return 命中的待办行，便于断言字段映射
     */
    private DashboardTodoVO findTodo(List<DashboardTodoVO> todoList, Long bizId) {
        return todoList.stream()
                .filter(todo -> bizId.equals(todo.bizId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("待办中未找到 bizId=" + bizId));
    }

    /**
     * 在近效期列表中定位指定批号的明细行。
     *
     * @param nearExpiryList 工作台近效期列表
     * @param batchNo        批次号
     * @return 命中的近效期行
     */
    private DashboardNearExpiryVO findNearExpiry(List<DashboardNearExpiryVO> nearExpiryList, String batchNo) {
        return nearExpiryList.stream()
                .filter(item -> batchNo.equals(item.batchNo()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("近效期清单中未找到 batchNo=" + batchNo));
    }

    /**
     * 在销售排行中定位指定商品的排行行。
     *
     * @param topProductList 工作台销售排行列表
     * @param targetProductId 商品主键
     * @return 命中的排行行
     */
    private DashboardTopProductVO findTopProduct(List<DashboardTopProductVO> topProductList,
                                                 Long targetProductId) {
        return topProductList.stream()
                .filter(item -> targetProductId.equals(item.productId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("销售排行中未找到 productId=" + targetProductId));
    }
}
