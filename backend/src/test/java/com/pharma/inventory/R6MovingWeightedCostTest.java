package com.pharma.inventory;

import com.pharma.inventory.entity.CostLedger;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
import com.pharma.sales.dto.SalesDeliveryCreateRequest;
import com.pharma.sales.dto.SalesDeliveryItemRequest;
import com.pharma.sales.dto.SalesDeliveryVO;
import com.pharma.sales.dto.SalesOrderCreateRequest;
import com.pharma.sales.dto.SalesOrderItemRequest;
import com.pharma.sales.dto.SalesOrderVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6 移动加权（商品级）：入库后新单价=(结存数量×结存单价 + 入库数量×入库单价)/(结存数量+入库数量)；
 * 出库按当前移动加权单价结转成本，出库不重算单价。
 */
class R6MovingWeightedCostTest extends AbstractInventoryTest {

    @Test
    void 移动加权入库与出库结转() {
        LocalDate today = LocalDate.now();

        // 采购订单 20 件，提交并审批通过
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, today,
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("20"), new BigDecimal("10")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover("PURCHASE_ORDER", po.id());

        // 入库批次 A：10 件 @ 5
        receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), "A",
                        today.minusDays(10), today.plusDays(100), new BigDecimal("5"), "INS-A"))));

        // 入库批次 B：10 件 @ 15
        receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), "B",
                        today.minusDays(10), today.plusDays(200), new BigDecimal("15"), "INS-B"))));

        // 移动加权 = (10*5 + 10*15) / 20 = 10
        assertThat(ledger.productWac(productId)).isEqualByComparingTo("10");

        CostLedger afterIn = costLedgerRepository.findFirstByProductIdOrderByIdDesc(productId).orElseThrow();
        assertThat(afterIn.getBalanceQty()).isEqualByComparingTo("20");
        assertThat(afterIn.getBalanceAmount()).isEqualByComparingTo("200");

        // 销售订单 10 件，提交并审批通过后出库
        SalesOrderVO so = salesOrderService.create(new SalesOrderCreateRequest(customerId, today,
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("10"), new BigDecimal("20")))));
        salesOrderService.submit(so.id());
        approveAsApprover("SALES_ORDER", so.id());

        // 出库操作员具备库存成本查看权限，返回的 unitCost 才可见（P1-23）
        SalesDeliveryVO delivery = callAs("cost-operator", List.of("inventory:cost:view"),
                () -> deliveryApproved(new SalesDeliveryCreateRequest(warehouseId, so.id(), today,
                        List.of(new SalesDeliveryItemRequest(productId, new BigDecimal("10"))))));

        // 出库结转成本 = 当前移动加权单价 10
        assertThat(delivery.items()).hasSize(1);
        assertThat(delivery.items().get(0).unitCost()).isEqualByComparingTo("10");

        // 出库后余额 = 200 - 100 = 100，数量 = 10（出库不重算单价）
        CostLedger afterOut = costLedgerRepository.findFirstByProductIdOrderByIdDesc(productId).orElseThrow();
        assertThat(afterOut.getBalanceQty()).isEqualByComparingTo("10");
        assertThat(afterOut.getBalanceAmount()).isEqualByComparingTo("100");
    }
}
