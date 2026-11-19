package com.pharma.trace.dto;

import java.util.List;

/**
 * 批次追溯结果（正向/逆向统一结构）：inbounds=采购入库链路，outbounds=销售出库链路。
 */
public record TraceResult(Long productId,
                          String batchNo,
                          List<TraceInbound> inbounds,
                          List<TraceOutbound> outbounds) {
}
