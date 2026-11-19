package com.pharma.purchase;

import com.pharma.common.BusinessException;
import com.pharma.finance.entity.ApInvoice;
import com.pharma.finance.repository.ApInvoiceRepository;
import com.pharma.inventory.AbstractInventoryTest;
import com.pharma.inventory.entity.StockTransaction;
import com.pharma.inventory.repository.StockTransactionRepository;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
import com.pharma.purchase.dto.PurchaseReceiveVO;
import com.pharma.purchase.dto.PurchaseReturnCreateRequest;
import com.pharma.purchase.dto.PurchaseReturnItemRequest;
import com.pharma.purchase.dto.PurchaseReturnVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.pharma.inventory.service.InventoryLedgerService.REF_PURCHASE_RETURN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采购退货（P1-11）：指定批次精确扣减（OUT ref_type=PURCHASE_RETURN）+ 负数应付红冲 + 不可为负。
 */
class PurchaseReturnTest extends AbstractInventoryTest {

    @Autowired
    private com.pharma.purchase.service.PurchaseReturnService purchaseReturnService;
    @Autowired
    private StockTransactionRepository stockTransactionRepository;
    @Autowired
    private ApInvoiceRepository apInvoiceRepository;

    private static final String BATCH = "PR-" + System.nanoTime();

    @Test
    void 采购退货扣减指定批次并生成应付红冲() {
        LocalDate today = LocalDate.now();
        PurchaseReceiveVO receive = received(today, new BigDecimal("10"), new BigDecimal("5"));

        PurchaseReturnVO ret = purchaseReturnService.create(new PurchaseReturnCreateRequest(
                receive.id(), supplierId, today, "质量退货",
                List.of(new PurchaseReturnItemRequest(productId, BATCH, new BigDecimal("3"), warehouseId))));

        // 批次十四：建单只记草稿，不扣库存、不生成红字应付
        assertThat(ret.status()).isEqualTo("DRAFT");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
        assertThat(apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RETURN", ret.id())).isEmpty();

        purchaseReturnService.submit(ret.id());
        approveAsApprover("PURCHASE_RETURN", ret.id());
        // 审批仅下发 WMS（PUSHED），回传实量后才扣库存并生成红字应付
        assertThat(purchaseReturnService.get(ret.id()).status()).isEqualTo("PUSHED");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
        wmsCallbackFull("PURCHASE_RETURN", ret.id());
        PurchaseReturnVO completed = purchaseReturnService.get(ret.id());

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.totalAmount()).isEqualByComparingTo("15");
        assertThat(completed.returnNo()).startsWith("PR");

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("7");

        List<StockTransaction> txns = stockTransactionRepository.findByRefTypeAndRefId(REF_PURCHASE_RETURN, ret.id());
        assertThat(txns).hasSize(1);
        assertThat(txns.get(0).getOpType()).isEqualTo("OUT");
        assertThat(txns.get(0).getQuantity()).isEqualByComparingTo("-3");

        List<ApInvoice> reds = apInvoiceRepository.findByBizTypeAndBizId("PURCHASE_RETURN", ret.id());
        assertThat(reds).hasSize(1);
        assertThat(reds.get(0).getInvoiceAmount()).isEqualByComparingTo("-15");
    }

    @Test
    void 退货数量超过批次库存被拒绝且库存不变() {
        LocalDate today = LocalDate.now();
        PurchaseReceiveVO receive = received(today, new BigDecimal("10"), new BigDecimal("5"));

        assertThatThrownBy(() -> purchaseReturnService.create(new PurchaseReturnCreateRequest(
                receive.id(), supplierId, today, "超量退货",
                List.of(new PurchaseReturnItemRequest(productId, BATCH, new BigDecimal("99"), warehouseId)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("库存不足");
                });

        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, BATCH, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("10");
    }

    private PurchaseReceiveVO received(LocalDate today, BigDecimal qty, BigDecimal cost) {
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, qty, cost))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        return receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, qty, BATCH,
                        today.minusDays(10), today.plusDays(180), cost, "INS-PR"))));
    }
}
