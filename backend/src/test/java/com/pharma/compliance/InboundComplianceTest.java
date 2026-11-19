package com.pharma.compliance;

import com.pharma.base.entity.Warehouse;
import com.pharma.common.BusinessException;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveItemRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-14/15/16 入库合规：效期必须晚于生产日期且晚于今天、检验报告单号必填、
 * 温控兼容矩阵（常温只入常温，阴凉可入阴凉/冷藏，冷藏只入冷藏）、同批号效期不得冲突。
 */
class InboundComplianceTest extends AbstractComplianceTest {

    private static final String PO = "PURCHASE_ORDER";

    @Test
    void 效期早于今天拒绝入库() {
        LocalDate today = LocalDate.now();
        PurchaseOrderVO po = approvedOrder(new BigDecimal("10"));

        assertThatThrownBy(() -> receive(po.id(), "IC-EXP-" + System.nanoTime(),
                today.minusDays(30), today.minusDays(1), "INS-IC"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("效期必须晚于今天");
                });
    }

    @Test
    void 效期不晚于生产日期拒绝入库() {
        LocalDate today = LocalDate.now();
        PurchaseOrderVO po = approvedOrder(new BigDecimal("10"));

        assertThatThrownBy(() -> receive(po.id(), "IC-PRD-" + System.nanoTime(),
                today.plusDays(10), today.plusDays(5), "INS-IC"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("效期必须晚于生产日期");
                });
    }

    @Test
    void 缺少检验报告单号拒绝入库() {
        LocalDate today = LocalDate.now();
        PurchaseOrderVO po = approvedOrder(new BigDecimal("10"));

        assertThatThrownBy(() -> receive(po.id(), "IC-INS-" + System.nanoTime(),
                today.minusDays(10), today.plusDays(180), " "))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("检验报告单号");
                });
    }

    @Test
    void 温控不兼容拒绝入库() {
        LocalDate today = LocalDate.now();
        var product = productRepository.findById(productId).orElseThrow();
        product.setStorageCond("COLD");
        productRepository.save(product);

        PurchaseOrderVO po = approvedOrder(new BigDecimal("10"));

        // 测试仓为 ROOM_TEMP，冷藏药品不可入
        assertThatThrownBy(() -> receive(po.id(), "IC-TEMP-" + System.nanoTime(),
                today.minusDays(10), today.plusDays(180), "INS-IC"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("温控不兼容");
                });
    }

    @Test
    void 常温药品不可入冷藏仓() {
        LocalDate today = LocalDate.now();
        var product = productRepository.findById(productId).orElseThrow();
        product.setStorageCond("ROOM_TEMP");
        productRepository.save(product);

        Warehouse cold = new Warehouse();
        cold.setName("冷藏仓-" + System.nanoTime());
        cold.setTempType("COLD");
        cold.setStatus("ACTIVE");
        Long coldWarehouseId = warehouseRepository.save(cold).getId();

        PurchaseOrderVO po = approvedOrder(new BigDecimal("10"));

        assertThatThrownBy(() -> purchaseReceiveService.create(new PurchaseReceiveCreateRequest(coldWarehouseId,
                po.id(), today,
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), "IC-ROOM-" + System.nanoTime(),
                        today.minusDays(10), today.plusDays(180), new BigDecimal("5"), "INS-IC")))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("温控不兼容");
                });
    }

    @Test
    void 同批号效期冲突拒绝入库() {
        LocalDate today = LocalDate.now();
        String batch = "IC-DUP-" + System.nanoTime();
        PurchaseOrderVO po = approvedOrder(new BigDecimal("20"));

        receive(po.id(), batch, today.minusDays(10), today.plusDays(100), "INS-IC1");

        assertThatThrownBy(() -> receive(po.id(), batch, today.minusDays(10), today.plusDays(200), "INS-IC2"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("同批号效期冲突");
                });
    }

    private PurchaseOrderVO approvedOrder(BigDecimal qty) {
        PurchaseOrderVO po = purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId,
                LocalDate.now(),
                List.of(new PurchaseOrderItemRequest(productId, qty, new BigDecimal("10")))));
        purchaseOrderService.submit(po.id());
        approveAsApprover(PO, po.id());
        return po;
    }

    private void receive(Long orderId, String batchNo, LocalDate produceDate, LocalDate expireDate,
                         String inspectionNo) {
        receiveApproved(new PurchaseReceiveCreateRequest(warehouseId, orderId, LocalDate.now(),
                List.of(new PurchaseReceiveItemRequest(productId, new BigDecimal("10"), batchNo,
                        produceDate, expireDate, new BigDecimal("5"), inspectionNo))));
    }
}
