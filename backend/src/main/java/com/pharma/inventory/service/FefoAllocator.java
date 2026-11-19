package com.pharma.inventory.service;

import com.pharma.inventory.entity.Stock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * FEFO 批次分配纯函数（#13）：零仓库依赖、零写库、零锁，供「事前批次预览」与「出库分配」共用。
 * 规则与 R1/R2 一致：效期升序（NULLS LAST）、同效期批号升序；仅 quantity > 0 且可售批次参与分配。
 * 不足时只置 blocked + blockReason，不抛异常（400 文案由调用方用 productId 组装，保证历史文案逐字不变）。
 */
public final class FefoAllocator {

    public static final String STATUS_SELLABLE = "SELLABLE";
    public static final String STATUS_NEAR_EXPIRY = "NEAR_EXPIRY";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private FefoAllocator() {
    }

    /** 可售判定：expire_date > 今天 + 阈值（严格大于），无有效期视为不可售。 */
    public static boolean isSellable(LocalDate expireDate, LocalDate today, int nearExpiryDays) {
        if (expireDate == null) {
            return false;
        }
        return expireDate.isAfter(today.plusDays(nearExpiryDays));
    }

    /**
     * 效期状态：已过期（expire_date &lt; 今天）/ 近效期（0 ~ 阈值天）/ 可售（&gt; 阈值天）。
     * 无有效期按 EXPIRED 处理（不可售，与 isSellable 口径一致）。
     */
    public static String expireStatus(LocalDate expireDate, LocalDate today, int nearExpiryDays) {
        if (expireDate == null || expireDate.isBefore(today)) {
            return STATUS_EXPIRED;
        }
        if (expireDate.isAfter(today.plusDays(nearExpiryDays))) {
            return STATUS_SELLABLE;
        }
        return STATUS_NEAR_EXPIRY;
    }

    /** 剩余天数：今天到期为 0，已过期为负；无有效期为 null。 */
    public static Long daysToExpire(LocalDate expireDate, LocalDate today) {
        if (expireDate == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(today, expireDate);
    }

    /** 单个批次的计划扣减量 */
    public record FefoLine(String batchNo, Long stockId, BigDecimal takeQty) {
    }

    /** 批次预览行（不含成本，避免成本脱敏越权） */
    public record FefoBatchView(String batchNo,
                                BigDecimal availableQty,
                                BigDecimal plannedQty,
                                LocalDate expireDate,
                                Long daysToExpire,
                                String expireStatus) {
    }

    /** 分配计划：blocked=true 时 lines 为空（不产生任何分步扣减） */
    public record FefoPlan(BigDecimal requestedQty,
                           BigDecimal totalSellable,
                           boolean blocked,
                           String blockReason,
                           List<FefoLine> lines,
                           List<FefoBatchView> batches) {
    }

    /**
     * 生成分配计划。入参批次可乱序，内部按 FEFO 重排；不修改任何入参实体。
     * requestedQty &lt;= 0 时不阻断、不分配（复刻历史行为，空计划）。
     */
    public static FefoPlan plan(List<Stock> batches, BigDecimal requestedQty, int nearExpiryDays, LocalDate today) {
        BigDecimal requested = requestedQty == null ? BigDecimal.ZERO : requestedQty;
        List<Stock> ordered = new ArrayList<>(batches == null ? List.of() : batches);
        ordered.sort(Comparator
                .comparing(Stock::getExpireDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Stock::getBatchNo, Comparator.nullsLast(Comparator.naturalOrder())));

        BigDecimal remaining = requested.compareTo(BigDecimal.ZERO) > 0 ? requested : BigDecimal.ZERO;
        BigDecimal totalSellable = BigDecimal.ZERO;
        List<FefoLine> lines = new ArrayList<>();
        List<FefoBatchView> views = new ArrayList<>();

        for (Stock s : ordered) {
            BigDecimal available = s.getQuantity() == null ? BigDecimal.ZERO : s.getQuantity();
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            boolean sellable = isSellable(s.getExpireDate(), today, nearExpiryDays);
            BigDecimal take = BigDecimal.ZERO;
            if (sellable) {
                totalSellable = totalSellable.add(available);
                if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                    take = available.min(remaining);
                    remaining = remaining.subtract(take);
                    lines.add(new FefoLine(s.getBatchNo(), s.getId(), take));
                }
            }
            views.add(new FefoBatchView(s.getBatchNo(), available, take, s.getExpireDate(),
                    daysToExpire(s.getExpireDate(), today), expireStatus(s.getExpireDate(), today, nearExpiryDays)));
        }

        boolean blocked = requested.compareTo(BigDecimal.ZERO) > 0 && totalSellable.compareTo(requested) < 0;
        if (blocked) {
            // 整单阻断语义：不产生任何分步计划（与 allocateOutbound 整体拒绝一致）
            List<FefoBatchView> zeroed = views.stream()
                    .map(v -> new FefoBatchView(v.batchNo(), v.availableQty(), BigDecimal.ZERO,
                            v.expireDate(), v.daysToExpire(), v.expireStatus()))
                    .toList();
            return new FefoPlan(requested, totalSellable, true,
                    "可售批次总量 " + totalSellable.toPlainString() + " < 需求量 " + requested.toPlainString(),
                    List.of(), zeroed);
        }
        return new FefoPlan(requested, totalSellable, false, null, List.copyOf(lines), List.copyOf(views));
    }
}
