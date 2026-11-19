package com.pharma.compliance;

import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Product;
import com.pharma.base.entity.Supplier;
import com.pharma.common.BusinessException;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.sales.dto.SalesOrderCreateRequest;
import com.pharma.sales.dto.SalesOrderItemRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3 首营阻断：供应商/客户/商品未过首营（非 APPROVED）开单返回 400；过首营后开单成功。
 */
class R3FirstAuditTest extends AbstractComplianceTest {

    @Test
    void 供应商未过首营开采购单被拒绝400() {
        Supplier pending = new Supplier();
        pending.setName("待审供应商");
        pending.setFirstAuditStatus("PENDING");
        pending.setStatus("ACTIVE");
        Long pid = supplierRepository.save(pending).getId();

        assertThatThrownBy(() -> purchaseOrderService.create(new PurchaseOrderCreateRequest(pid, LocalDate.now(),
                List.of(new PurchaseOrderItemRequest(productId, BigDecimal.ONE, BigDecimal.TEN)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 商品未过首营开采购单被拒绝400() {
        Product pending = new Product();
        pending.setCode("PP-" + System.nanoTime());
        pending.setGenericName("待审药品");
        pending.setFirstAuditStatus("PENDING");
        pending.setStatus("ACTIVE");
        Long pid = productRepository.save(pending).getId();

        assertThatThrownBy(() -> purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, LocalDate.now(),
                List.of(new PurchaseOrderItemRequest(pid, BigDecimal.ONE, BigDecimal.TEN)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 供应商过首营后开采购单成功() {
        Supplier s = new Supplier();
        s.setName("过审供应商");
        s.setFirstAuditStatus("PENDING");
        s.setStatus("ACTIVE");
        Long sid = supplierRepository.save(s).getId();

        firstAuditService.approve("supplier", sid, null);

        PurchaseOrderVO vo = purchaseOrderService.create(new PurchaseOrderCreateRequest(sid, LocalDate.now(),
                List.of(new PurchaseOrderItemRequest(productId, BigDecimal.ONE, BigDecimal.TEN))));
        assertThat(vo.id()).isNotNull();
        assertThat(vo.status()).isEqualTo("DRAFT");
    }

    @Test
    void 客户未过首营开销售单被拒绝400() {
        Customer pending = new Customer();
        pending.setName("待审客户");
        pending.setFirstAuditStatus("PENDING");
        pending.setStatus("ACTIVE");
        Long cid = customerRepository.save(pending).getId();

        assertThatThrownBy(() -> salesOrderService.create(new SalesOrderCreateRequest(cid, LocalDate.now(),
                List.of(new SalesOrderItemRequest(productId, BigDecimal.ONE, BigDecimal.TEN)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    void 商品未过首营开销售单被拒绝400() {
        Product pending = new Product();
        pending.setCode("SP-" + System.nanoTime());
        pending.setGenericName("待审药品2");
        pending.setFirstAuditStatus("PENDING");
        pending.setStatus("ACTIVE");
        Long pid = productRepository.save(pending).getId();

        assertThatThrownBy(() -> salesOrderService.create(new SalesOrderCreateRequest(customerId, LocalDate.now(),
                List.of(new SalesOrderItemRequest(pid, BigDecimal.ONE, BigDecimal.TEN)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }
}
