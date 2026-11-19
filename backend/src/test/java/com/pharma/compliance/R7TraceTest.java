package com.pharma.compliance;

import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
import com.pharma.purchase.dto.PurchaseReceiveVO;
import com.pharma.sales.dto.SalesDeliveryCreateRequest;
import com.pharma.sales.dto.SalesDeliveryItemRequest;
import com.pharma.sales.dto.SalesDeliveryVO;
import com.pharma.sales.dto.SalesOrderCreateRequest;
import com.pharma.sales.dto.SalesOrderItemRequest;
import com.pharma.sales.dto.SalesOrderVO;
import com.pharma.trace.dto.TraceResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7 追溯完整性：正向（批次→采购入库→销售出库→客户）与逆向（批次→采购入库→供应商）链路完整。
 */
class R7TraceTest extends AbstractComplianceTest {

    @Test
    void 正向与逆向追溯链路完整() {
        LocalDate today = LocalDate.now();
        String batch = "B7-" + System.nanoTime();

        // 采购入库
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("20"), new BigDecimal("10")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());
        PurchaseReceiveVO receive = receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("20"), batch,
                        today.minusDays(10), today.plusDays(200), new BigDecimal("5"), "INS-7"))));

        // 销售出库
        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20")))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());
        SalesDeliveryVO delivery = deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("10")))));

        // 正向：批次 -> 入库 -> 出库 -> 客户
        TraceResult forward = traceService.forward(productId, batch);
        assertThat(forward.inbounds()).hasSize(1);
        assertThat(forward.inbounds().get(0).receiveNo()).isEqualTo(receive.receiveNo());
        assertThat(forward.inbounds().get(0).supplierId()).isEqualTo(supplierId);
        assertThat(forward.inbounds().get(0).orderNo()).isEqualTo(po.orderNo());
        assertThat(forward.outbounds()).hasSize(1);
        assertThat(forward.outbounds().get(0).deliveryNo()).isEqualTo(delivery.deliveryNo());
        assertThat(forward.outbounds().get(0).customerId()).isEqualTo(customerId);
        assertThat(forward.outbounds().get(0).orderNo()).isEqualTo(so.orderNo());

        // 逆向：批次 -> 出库 -> 客户 / 入库 -> 供应商（同链路反向视角）
        TraceResult backward = traceService.backward(productId, batch);
        assertThat(backward.inbounds()).hasSize(1);
        assertThat(backward.inbounds().get(0).supplierId()).isEqualTo(supplierId);
        assertThat(backward.outbounds()).hasSize(1);
        assertThat(backward.outbounds().get(0).customerId()).isEqualTo(customerId);
    }
}
