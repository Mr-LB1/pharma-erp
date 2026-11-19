package com.pharma.compliance;

import com.pharma.common.BusinessException;
import com.pharma.common.DateValidator;
import com.pharma.inventory.AbstractInventoryTest;
import com.pharma.inventory.dto.StockTransferCreateRequest;
import com.pharma.inventory.dto.StockTransferItemRequest;
import com.pharma.inventory.service.StockTransferService;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReturnCreateRequest;
import com.pharma.purchase.service.PurchaseReturnService;
import com.pharma.sales.dto.SalesDeliveryCreateRequest;
import com.pharma.sales.dto.SalesOrderCreateRequest;
import com.pharma.sales.dto.SalesOrderItemRequest;
import com.pharma.sales.dto.SalesReturnCreateRequest;
import com.pharma.sales.service.SalesReturnService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2-6 单据业务日期校验：未来日期与超早日期（>365 天）一律 400，正常日期放行。
 */
class BizDateValidationTest extends AbstractInventoryTest {

    @Autowired
    private DateValidator dateValidator;
    @Autowired
    private PurchaseReturnService purchaseReturnService;
    @Autowired
    private StockTransferService stockTransferService;
    @Autowired
    private SalesReturnService salesReturnService;

    @Test
    @DisplayName("DateValidator：未来日期/超 365 天日期拒绝，当天放行")
    void validatorRules() {
        LocalDate today = LocalDate.now();
        assertThatThrownBy(() -> dateValidator.validateBizDate(today.plusDays(1), "业务日期"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能晚于今天")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));

        assertThatThrownBy(() -> dateValidator.validateBizDate(today.minusDays(400), "业务日期"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超出允许范围")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));

        assertThatCode(() -> dateValidator.validateBizDate(today, "业务日期")).doesNotThrowAnyException();
        assertThatCode(() -> dateValidator.validateBizDate(null, "业务日期")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("采购订单：未来日期 400，超早日期 400")
    void purchaseOrderDate() {
        LocalDate today = LocalDate.now();
        assertThatThrownBy(() -> purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId,
                today.plusDays(1),
                List.of(new PurchaseOrderItemRequest(productId, BigDecimal.ONE, BigDecimal.ONE)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单日期不能晚于今天");

        assertThatThrownBy(() -> purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId,
                today.minusDays(400),
                List.of(new PurchaseOrderItemRequest(productId, BigDecimal.ONE, BigDecimal.ONE)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单日期超出允许范围");
    }

    @Test
    @DisplayName("销售订单/入库/出库/退货/调拨：未来日期均 400")
    void otherDocumentsRejectFutureDate() {
        LocalDate today = LocalDate.now();
        LocalDate future = today.plusDays(3);

        assertThatThrownBy(() -> salesOrderService.create(new SalesOrderCreateRequest(customerId, future,
                List.of(new SalesOrderItemRequest(productId, BigDecimal.ONE, BigDecimal.ONE)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单日期不能晚于今天");

        assertThatThrownBy(() -> purchaseReceiveService.create(new PurchaseReceiveCreateRequest(
                warehouseId, 1L, future, List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("入库日期不能晚于今天");

        assertThatThrownBy(() -> salesDeliveryService.create(new SalesDeliveryCreateRequest(
                warehouseId, 1L, future, List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("出库日期不能晚于今天");

        assertThatThrownBy(() -> salesReturnService.create(new SalesReturnCreateRequest(
                1L, null, future, "退货", List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("退货日期不能晚于今天");

        assertThatThrownBy(() -> purchaseReturnService.create(new PurchaseReturnCreateRequest(
                null, supplierId, future, "退货", List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("退货日期不能晚于今天");

        assertThatThrownBy(() -> stockTransferService.create(new StockTransferCreateRequest(
                warehouseId, 2L, future, "调拨", List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("调拨日期不能晚于今天");
    }
}
