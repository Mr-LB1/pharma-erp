package com.pharma.reports.dto;

import java.math.BigDecimal;

/**
 * 进销存汇总（期初/入库/出库/期末，按商品）。
 * 金额口径取 cost_ledger（商品级移动加权）：期初=start 前累计、入库=区间正、出库=区间负绝对值、期末=期初+入库-出库。
 */
public record StockSummaryVO(Long productId,
                             String productCode,
                             String productName,
                             String genericName,
                             String tradeName,
                             String spec,
                             String manufacturer,
                             BigDecimal openingQty,
                             BigDecimal inboundQty,
                             BigDecimal outboundQty,
                             BigDecimal closingQty,
                             BigDecimal openingAmount,
                             BigDecimal inboundAmount,
                             BigDecimal outboundAmount,
                             BigDecimal closingAmount) {
}
