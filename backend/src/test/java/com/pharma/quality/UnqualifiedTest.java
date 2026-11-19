package com.pharma.quality;

import com.pharma.base.entity.Warehouse;
import com.pharma.common.BusinessException;
import com.pharma.inventory.AbstractInventoryTest;
import com.pharma.quality.dto.UnqualifiedCreateRequest;
import com.pharma.quality.dto.UnqualifiedDisposeRequest;
import com.pharma.quality.dto.UnqualifiedVO;
import com.pharma.quality.service.UnqualifiedService;
import com.pharma.system.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-17 不合格品：登记只能扣减指定仓库库存（他仓有货也不得串仓），
 * 处理需记录销毁方式/时间/操作人并写审计日志。
 */
class UnqualifiedTest extends AbstractInventoryTest {

    @Autowired
    private UnqualifiedService unqualifiedService;
    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void 登记限定仓库并在销毁时留痕() {
        String batch = "UQ-" + System.nanoTime();
        createStock(batch, LocalDate.now().plusDays(180), new BigDecimal("10"), new BigDecimal("5"));

        Warehouse other = new Warehouse();
        other.setName("他仓-" + System.nanoTime());
        other.setTempType("ROOM_TEMP");
        other.setStatus("ACTIVE");
        Long otherWarehouseId = warehouseRepository.save(other).getId();

        // 指定仓库无该批次库存 → 拒绝，不跨仓扣减
        assertThatThrownBy(() -> unqualifiedService.register(
                new UnqualifiedCreateRequest(productId, otherWarehouseId, batch, BigDecimal.ONE, "包装破损")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("不足");
                });

        UnqualifiedVO registered = unqualifiedService.register(
                new UnqualifiedCreateRequest(productId, warehouseId, batch, new BigDecimal("4"), "包装破损"));
        assertThat(registered.warehouseId()).isEqualTo(warehouseId);
        assertThat(registered.status()).isEqualTo("PENDING");
        assertThat(stockRepository.findByProductIdAndBatchNoAndLocationId(productId, batch, warehouseId)
                .orElseThrow().getQuantity()).isEqualByComparingTo("6");

        UnqualifiedVO disposed = unqualifiedService.dispose(registered.id(),
                new UnqualifiedDisposeRequest("销毁", LocalDate.now(), "监督员A"));
        assertThat(disposed.status()).isEqualTo("DISPOSED");
        assertThat(disposed.disposeMethod()).isEqualTo("销毁");
        assertThat(disposed.disposedAt()).isNotNull();
        assertThat(disposed.disposedBy()).isEqualTo("监督员A");
        assertThat(auditLogRepository.findAll())
                .anyMatch(log -> "UNQUALIFIED_DISPOSE".equals(log.getAction())
                        && registered.id().equals(log.getObjectId()));
    }
}
