package com.pharma.compliance;

import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Supplier;
import com.pharma.common.BusinessException;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.quality.dto.LicenseWarning;
import com.pharma.sales.dto.SalesOrderCreateRequest;
import com.pharma.sales.dto.SalesOrderItemRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R4 证照效期预警/锁交易：证照过期开单返回 400；预警列表含临期证照。
 */
class R4LicenseExpiryTest extends AbstractComplianceTest {

    @Test
    void 供应商证照过期开采购单被拒绝400() {
        Supplier s = new Supplier();
        s.setName("过期供应商");
        s.setFirstAuditStatus("APPROVED");
        s.setStatus("ACTIVE");
        s.setLicenseNo("LIC-EXPIRED");
        s.setLicenseExpire(LocalDate.now().minusDays(1));
        Long sid = supplierRepository.save(s).getId();

        assertThatThrownBy(() -> purchaseOrderService.create(new PurchaseOrderCreateRequest(sid, LocalDate.now(),
                List.of(new PurchaseOrderItemRequest(productId, BigDecimal.ONE, BigDecimal.TEN)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 客户证照过期开销售单被拒绝400() {
        Customer c = new Customer();
        c.setName("过期客户");
        c.setFirstAuditStatus("APPROVED");
        c.setStatus("ACTIVE");
        c.setLicenseNo("LIC-EXPIRED");
        c.setLicenseExpire(LocalDate.now().minusDays(1));
        Long cid = customerRepository.save(c).getId();

        assertThatThrownBy(() -> salesOrderService.create(new SalesOrderCreateRequest(cid, LocalDate.now(),
                List.of(new SalesOrderItemRequest(productId, BigDecimal.ONE, BigDecimal.TEN)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 证照预警列表含临期证照且排除远期证照() {
        LocalDate today = LocalDate.now();

        Supplier near = new Supplier();
        near.setName("临期供应商");
        near.setFirstAuditStatus("APPROVED");
        near.setStatus("ACTIVE");
        near.setLicenseNo("LIC-NEAR");
        near.setLicenseExpire(today.plusDays(30));
        Long nearId = supplierRepository.save(near).getId();

        Customer far = new Customer();
        far.setName("远期客户");
        far.setFirstAuditStatus("APPROVED");
        far.setStatus("ACTIVE");
        far.setLicenseNo("LIC-FAR");
        far.setLicenseExpire(today.plusDays(365));
        Long farId = customerRepository.save(far).getId();

        List<LicenseWarning> warnings = firstAuditService.expiringLicenses(90);

        assertThat(warnings)
                .anyMatch(w -> "supplier".equals(w.type()) && w.id().equals(nearId));
        assertThat(warnings)
                .noneMatch(w -> "customer".equals(w.type()) && w.id().equals(farId));
    }
}
