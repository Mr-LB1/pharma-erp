package com.pharma.inventory;

import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.service.FefoAllocator;
import com.pharma.inventory.service.FefoAllocator.FefoBatchView;
import com.pharma.inventory.service.FefoAllocator.FefoLine;
import com.pharma.inventory.service.FefoAllocator.FefoPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #13 FEFO 纯函数分配器单测（不启动 Spring，零数据库依赖）。
 * 覆盖：乱序输入排序、跨批拆分、恰好等量、不足 blocked、过期/近效期剔除、0/30/31 天边界、状态映射。
 */
class FefoAllocatorTest {

    private static final int NEAR = 30;

    private Stock stock(long id, String batchNo, LocalDate expireDate, String qty) {
        Stock s = new Stock();
        s.setId(id);
        s.setBatchNo(batchNo);
        s.setExpireDate(expireDate);
        s.setQuantity(new BigDecimal(qty));
        s.setUnitCost(new BigDecimal("1.00"));
        return s;
    }

    @Test
    void 乱序输入按FEFO排序并跨批拆分() {
        LocalDate today = LocalDate.now();
        Stock late = stock(2L, "LATE", today.plusDays(200), "10");
        Stock early = stock(1L, "EARLY", today.plusDays(100), "10");
        List<Stock> input = new ArrayList<>(List.of(late, early));

        FefoPlan plan = FefoAllocator.plan(input, new BigDecimal("15"), NEAR, today);

        assertThat(plan.blocked()).isFalse();
        assertThat(plan.lines()).extracting(FefoLine::batchNo).containsExactly("EARLY", "LATE");
        assertThat(plan.lines()).extracting(FefoLine::takeQty)
                .containsExactly(new BigDecimal("10"), new BigDecimal("5"));
        assertThat(plan.totalSellable()).isEqualByComparingTo("20");
        // 预览批次同样按 FEFO 顺序
        assertThat(plan.batches()).extracting(FefoBatchView::batchNo).containsExactly("EARLY", "LATE");
        // 纯函数不修改入参
        assertThat(input).extracting(Stock::getQuantity)
                .containsExactly(new BigDecimal("10"), new BigDecimal("10"));
    }

    @Test
    void 同效期按批号升序() {
        LocalDate today = LocalDate.now();
        LocalDate same = today.plusDays(120);
        FefoPlan plan = FefoAllocator.plan(List.of(
                stock(2L, "B-002", same, "10"),
                stock(1L, "B-001", same, "10")), new BigDecimal("5"), NEAR, today);

        assertThat(plan.lines()).hasSize(1);
        assertThat(plan.lines().get(0).batchNo()).isEqualTo("B-001");
    }

    @Test
    void 恰好等量不阻断且全部计划量等于需求() {
        LocalDate today = LocalDate.now();
        FefoPlan plan = FefoAllocator.plan(List.of(
                stock(1L, "A", today.plusDays(100), "9.5")), new BigDecimal("9.5"), NEAR, today);

        assertThat(plan.blocked()).isFalse();
        assertThat(plan.blockReason()).isNull();
        assertThat(plan.totalSellable()).isEqualByComparingTo("9.5");
        assertThat(plan.lines()).hasSize(1);
        assertThat(plan.lines().get(0).takeQty()).isEqualByComparingTo("9.5");
    }

    @Test
    void 可售总量不足时blocked且文案含总量与需求() {
        LocalDate today = LocalDate.now();
        FefoPlan plan = FefoAllocator.plan(List.of(
                stock(1L, "A", today.plusDays(100), "5")), new BigDecimal("8"), NEAR, today);

        assertThat(plan.blocked()).isTrue();
        assertThat(plan.blockReason()).contains("5").contains("8");
        assertThat(plan.totalSellable()).isEqualByComparingTo("5");
        assertThat(plan.lines()).isEmpty();
    }

    @Test
    void 过期与近效期批次被剔除且状态正确() {
        LocalDate today = LocalDate.now();
        FefoPlan plan = FefoAllocator.plan(List.of(
                stock(1L, "EXPIRED", today.minusDays(1), "10"),
                stock(2L, "NEAR", today.plusDays(10), "10"),
                stock(3L, "GOOD", today.plusDays(100), "10")), new BigDecimal("5"), NEAR, today);

        assertThat(plan.totalSellable()).isEqualByComparingTo("10");
        assertThat(plan.lines()).extracting(FefoLine::batchNo).containsExactly("GOOD");
        assertThat(plan.batches()).extracting(FefoBatchView::expireStatus)
                .containsExactly(FefoAllocator.STATUS_EXPIRED, FefoAllocator.STATUS_NEAR_EXPIRY,
                        FefoAllocator.STATUS_SELLABLE);
        assertThat(plan.batches()).extracting(FefoBatchView::plannedQty)
                .containsExactly(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5"));
    }

    @Test
    void 仅有近效期批次时阻断且可售总量为0() {
        LocalDate today = LocalDate.now();
        FefoPlan plan = FefoAllocator.plan(List.of(
                stock(1L, "NEAR", today.plusDays(10), "10")), new BigDecimal("1"), NEAR, today);

        assertThat(plan.blocked()).isTrue();
        assertThat(plan.totalSellable()).isEqualByComparingTo("0");
        assertThat(plan.lines()).isEmpty();
        assertThat(plan.batches()).hasSize(1);
        assertThat(plan.batches().get(0).expireStatus()).isEqualTo(FefoAllocator.STATUS_NEAR_EXPIRY);
    }

    @Test
    void 效期0天30天31天边界() {
        LocalDate today = LocalDate.now();
        assertThat(FefoAllocator.expireStatus(today, today, NEAR)).isEqualTo(FefoAllocator.STATUS_NEAR_EXPIRY);
        assertThat(FefoAllocator.expireStatus(today.plusDays(30), today, NEAR)).isEqualTo(FefoAllocator.STATUS_NEAR_EXPIRY);
        assertThat(FefoAllocator.expireStatus(today.plusDays(31), today, NEAR)).isEqualTo(FefoAllocator.STATUS_SELLABLE);

        assertThat(FefoAllocator.isSellable(today, today, NEAR)).isFalse();
        assertThat(FefoAllocator.isSellable(today.plusDays(30), today, NEAR)).isFalse();
        assertThat(FefoAllocator.isSellable(today.plusDays(31), today, NEAR)).isTrue();
    }

    @Test
    void daysToExpire与状态映射() {
        LocalDate today = LocalDate.now();
        assertThat(FefoAllocator.daysToExpire(today.plusDays(31), today)).isEqualTo(31L);
        assertThat(FefoAllocator.daysToExpire(today, today)).isEqualTo(0L);
        assertThat(FefoAllocator.daysToExpire(today.minusDays(2), today)).isEqualTo(-2L);
        assertThat(FefoAllocator.daysToExpire(null, today)).isNull();
        assertThat(FefoAllocator.expireStatus(today.minusDays(2), today, NEAR))
                .isEqualTo(FefoAllocator.STATUS_EXPIRED);
        // 品种级阈值覆盖：阈值 60 天时 31 天变近效期
        assertThat(FefoAllocator.expireStatus(today.plusDays(31), today, 60))
                .isEqualTo(FefoAllocator.STATUS_NEAR_EXPIRY);
    }

    @Test
    void plannedQty合计不超过需求量() {
        LocalDate today = LocalDate.now();
        FefoPlan plan = FefoAllocator.plan(List.of(
                stock(1L, "A", today.plusDays(100), "4"),
                stock(2L, "B", today.plusDays(110), "4"),
                stock(3L, "C", today.plusDays(120), "4")), new BigDecimal("7"), NEAR, today);

        BigDecimal plannedSum = plan.lines().stream().map(FefoLine::takeQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(plannedSum).isEqualByComparingTo("7");
        assertThat(plannedSum).isLessThanOrEqualTo(plan.requestedQty());
        assertThat(plan.lines()).extracting(FefoLine::batchNo).containsExactly("A", "B");
    }

    @Test
    void 需求量非正时无阻断无分配() {
        LocalDate today = LocalDate.now();
        List<Stock> batches = List.of(stock(1L, "A", today.plusDays(100), "10"));

        FefoPlan zero = FefoAllocator.plan(batches, BigDecimal.ZERO, NEAR, today);
        assertThat(zero.blocked()).isFalse();
        assertThat(zero.lines()).isEmpty();
        assertThat(zero.totalSellable()).isEqualByComparingTo("10");

        FefoPlan negative = FefoAllocator.plan(batches, new BigDecimal("-1"), NEAR, today);
        assertThat(negative.blocked()).isFalse();
        assertThat(negative.lines()).isEmpty();
    }

    @Test
    void 零库存批次不进入预览() {
        LocalDate today = LocalDate.now();
        FefoPlan plan = FefoAllocator.plan(List.of(
                stock(1L, "EMPTY", today.plusDays(100), "0"),
                stock(2L, "GOOD", today.plusDays(110), "3")), new BigDecimal("1"), NEAR, today);

        assertThat(plan.batches()).extracting(FefoBatchView::batchNo).containsExactly("GOOD");
        assertThat(plan.totalSellable()).isEqualByComparingTo("3");
    }
}
