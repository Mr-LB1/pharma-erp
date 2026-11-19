package com.pharma.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PurchaseReceiveItemVO(Long id,
                                    Long productId,
                                    String productName,
                                    BigDecimal quantity,
                                    String batchNo,
                                    LocalDate produceDate,
                                    LocalDate expireDate,
                                    BigDecimal unitCost,
                                    String inspectionNo,
                                    BigDecimal actualQty,
                                    BigDecimal qtyDiff) {
}
