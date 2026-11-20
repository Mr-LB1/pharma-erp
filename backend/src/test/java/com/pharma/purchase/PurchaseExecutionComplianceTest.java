package com.pharma.purchase;

import com.pharma.base.entity.Product;
import com.pharma.base.entity.Supplier;
import com.pharma.common.BusinessException;
import com.pharma.inventory.AbstractInventoryTest;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-D 执行前合规复核回归测试（真实 PG + 事务回滚）：
 * 采购订单/入库单/退货单审批后，主数据可能变化（供应商证照过期、商品停用），
 * WMS 回传执行时必须按当前数据复核并 400，且不得落库存/扣库存。
 */
class PurchaseExecutionComplianceTest extends AbstractInventoryTest {

    /** 业务日期统一取当天（测试内不跨日）。 */
    private static final LocalDate TODAY = LocalDate.now();

    /**
     * 入库：审批（PUSHED）后供应商证照过期，WMS 回传必须 400，不创建批次库存，单据仍为 PUSHED。
     */
    @Test
    void 审批后供应商证照过期WMS回传入库被拒且不落库存() {
        String batch = "P1D-IN-LIC-" + System.nanoTime();
        PurchaseReceiveVO receive = pushedReceive(batch);
        expireSupplierLicense();

        assertThatThrownBy(() -> purchaseReceiveService.wmsCallback(receive.id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("证照已过期").contains(supplierName());
                });
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)).isEmpty();
        assertThat(purchaseReceiveService.get(receive.id()).status()).isEqualTo("PUSHED");
    }

    /**
     * 入库：审批后商品被停用（status != ACTIVE），WMS 回传必须 400，不落库存。
     */
    @Test
    void 审批后商品停用WMS回传入库被拒且不落库存() {
        String batch = "P1D-IN-STS-" + System.nanoTime();
        PurchaseReceiveVO receive = pushedReceive(batch);
        deactivateProduct();

        assertThatThrownBy(() -> purchaseReceiveService.wmsCallback(receive.id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("商品非启用状态").contains(productName());
                });
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)).isEmpty();
        assertThat(purchaseReceiveService.get(receive.id()).status()).isEqualTo("PUSHED");
    }

    /**
     * 退货：审批（PUSHED）后供应商证照过期，WMS 回传必须 400，不扣减库存，单据仍为 PUSHED。
     */
    @Test
    void 审批后供应商证照过期WMS回传退货被拒且库存不变() {
        String batch = "P1D-PR-LIC-" + System.nanoTime();
        PurchaseReceiveVO receive = receivedApproved(batch);
        PurchaseReturnVO ret = pushedReturn(receive, batch, new BigDecimal("3"));
        expireSupplierLicense();

        assertThatThrownBy(() -> purchaseReturnService.wmsCallback(ret.id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("证照已过期").contains(supplierName());
                });
        assertThat(stockQty(batch)).isEqualByComparingTo("10");
        assertThat(purchaseReturnService.get(ret.id()).status()).isEqualTo("PUSHED");
    }

    /**
     * 退货：审批后商品被停用（status != ACTIVE），WMS 回传必须 400，不扣减库存。
     */
    @Test
    void 审批后商品停用WMS回传退货被拒且库存不变() {
        String batch = "P1D-PR-STS-" + System.nanoTime();
        PurchaseReceiveVO receive = receivedApproved(batch);
        PurchaseReturnVO ret = pushedReturn(receive, batch, new BigDecimal("3"));
        deactivateProduct();

        assertThatThrownBy(() -> purchaseReturnService.wmsCallback(ret.id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("商品非启用状态").contains(productName());
                });
        assertThat(stockQty(batch)).isEqualByComparingTo("10");
        assertThat(purchaseReturnService.get(ret.id()).status()).isEqualTo("PUSHED");
    }

    /** 建采购订单并审批通过（前置：供应商/商品合规，供后续入库单建单校验通过）。 */
    private PurchaseOrderVO approvedOrder(BigDecimal qty) {
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, TODAY,
                List.of(new PurchaseOrderItemRequest(productId, qty, new BigDecimal("5")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        return po;
    }

    /** 采购入库单走到 PUSHED（已下发 WMS，尚未回传执行）。 */
    private PurchaseReceiveVO pushedReceive(String batch) {
        PurchaseOrderVO po = approvedOrder(new BigDecimal("10"));
        PurchaseReceiveVO receive = purchaseReceiveService.create(new PurchaseReceiveCreateRequest(warehouseId,
                po.id(), TODAY, List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                        TODAY.minusDays(10), TODAY.plusDays(180), new BigDecimal("5"), "INS-P1D"))));
        purchaseReceiveService.submit(receive.id());
        approveAsApprover("PURCHASE_RECEIVE", receive.id());
        return purchaseReceiveService.get(receive.id());
    }

    /** 全量入库完成（按单据量回传），构造退货前置库存。 */
    private PurchaseReceiveVO receivedApproved(String batch) {
        PurchaseOrderVO po = approvedOrder(new BigDecimal("10"));
        return receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), TODAY,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batch,
                        TODAY.minusDays(10), TODAY.plusDays(180), new BigDecimal("5"), "INS-P1D"))));
    }

    /** 采购退货单走到 PUSHED（已下发 WMS，尚未回传执行）。 */
    private PurchaseReturnVO pushedReturn(PurchaseReceiveVO receive, String batch, BigDecimal qty) {
        PurchaseReturnVO ret = purchaseReturnService.create(new PurchaseReturnCreateRequest(receive.id(), supplierId,
                TODAY, "P1-D 执行前复核", List.of(new PurchaseReturnItemRequest(productId, batch, qty, warehouseId))));
        purchaseReturnService.submit(ret.id());
        approveAsApprover("PURCHASE_RETURN", ret.id());
        return purchaseReturnService.get(ret.id());
    }

    /** 模拟审批后供应商证照过期（早于今天一天）。 */
    private void expireSupplierLicense() {
        Supplier supplier = supplierRepository.findById(supplierId).orElseThrow();
        supplier.setLicenseExpire(TODAY.minusDays(1));
        supplierRepository.save(supplier);
    }

    /** 模拟审批后商品被停用。 */
    private void deactivateProduct() {
        Product product = productRepository.findById(productId).orElseThrow();
        product.setStatus("INACTIVE");
        productRepository.save(product);
    }

    /** 当前测试供应商名称（用于断言错误文案含名称）。 */
    private String supplierName() {
        return supplierRepository.findById(supplierId).orElseThrow().getName();
    }

    /** 当前测试商品通用名（用于断言错误文案含名称）。 */
    private String productName() {
        return productRepository.findById(productId).orElseThrow().getGenericName();
    }

    /** 指定批次在测试仓的当前库存数量。 */
    private BigDecimal stockQty(String batch) {
        return stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity();
    }
}
