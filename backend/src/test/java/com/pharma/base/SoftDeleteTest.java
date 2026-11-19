package com.pharma.base;

import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Product;
import com.pharma.base.entity.Supplier;
import com.pharma.base.entity.Warehouse;
import com.pharma.base.service.CustomerService;
import com.pharma.base.service.ProductService;
import com.pharma.base.service.SupplierService;
import com.pharma.base.service.WarehouseService;
import com.pharma.common.BusinessException;
import com.pharma.inventory.AbstractInventoryTest;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderItemRequest;
import com.pharma.sales.dto.SalesOrderCreateRequest;
import com.pharma.sales.dto.SalesOrderItemRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-4 软删除：主数据删除只置 INACTIVE；被业务引用时拒绝删除；
 * 软删后列表/详情不可见，但历史单据与库存仍可追溯。
 */
class SoftDeleteTest extends AbstractInventoryTest {

    @Autowired
    private ProductService productService;
    @Autowired
    private SupplierService supplierService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private WarehouseService warehouseService;

    @Test
    void 无引用商品删除为软删且列表详情不可见() {
        Product product = new Product();
        product.setCode("SD-" + System.nanoTime());
        product.setGenericName("软删测试商品");
        product.setStatus("ACTIVE");
        product.setFirstAuditStatus("APPROVED");
        Long id = productService.create(product).getId();

        productService.delete(id);

        assertThat(productRepository.findById(id))
                .get()
                .extracting(Product::getStatus)
                .isEqualTo("INACTIVE");
        assertThat(productService.list(product.getCode(), "ACTIVE", 1, 10).getRecords()).isEmpty();
        assertThatThrownBy(() -> productService.get(id))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(404));

        // 再次删除幂等，不报错
        productService.delete(id);
    }

    @Test
    void 被库存引用的商品不可删除() {
        createStock("SD-B-" + System.nanoTime(), LocalDate.now().plusDays(180), new BigDecimal("10"), new BigDecimal("5"));

        assertThatThrownBy(() -> productService.delete(productId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("引用");
                });
        assertThat(productRepository.findById(productId).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void 被采购订单引用的供应商不可删除() {
        purchaseOrderService.create(new PurchaseOrderCreateRequest(supplierId, LocalDate.now(),
                List.of(new PurchaseOrderItemRequest(productId, new BigDecimal("5"), new BigDecimal("10")))));

        assertThatThrownBy(() -> supplierService.delete(supplierId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("采购订单");
                });
        assertThat(supplierRepository.findById(supplierId).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void 被销售订单引用的客户不可删除() {
        salesOrderService.create(new SalesOrderCreateRequest(customerId, LocalDate.now(),
                List.of(new SalesOrderItemRequest(productId, new BigDecimal("5"), new BigDecimal("20")))));

        assertThatThrownBy(() -> customerService.delete(customerId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("销售订单");
                });
        assertThat(customerRepository.findById(customerId).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void 被库存引用的仓库不可删除() {
        createStock("SD-W-" + System.nanoTime(), LocalDate.now().plusDays(180), new BigDecimal("1"), new BigDecimal("1"));

        assertThatThrownBy(() -> warehouseService.delete(warehouseId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("引用");
                });
        assertThat(warehouseRepository.findById(warehouseId).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void 列表状态参数可查已停用并恢复启用() {
        Product product = new Product();
        product.setCode("SD-ST-" + System.nanoTime());
        product.setGenericName("状态过滤商品");
        product.setStatus("ACTIVE");
        product.setFirstAuditStatus("APPROVED");
        Long id = productService.create(product).getId();

        productService.delete(id);
        assertThat(productService.list(product.getCode(), "ACTIVE", 1, 10).getRecords()).isEmpty();
        assertThat(productService.list(product.getCode(), "INACTIVE", 1, 10).getRecords()).hasSize(1);
        assertThat(productService.list(product.getCode(), "ALL", 1, 10).getRecords()).hasSize(1);
        // 默认（不传 status）等价 ACTIVE
        assertThat(productService.list(product.getCode(), null, 1, 10).getRecords()).isEmpty();
        // 非法状态值显式 400，避免静默返回空列表
        assertThatThrownBy(() -> productService.list(product.getCode(), "BAD", 1, 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(400));

        // 恢复启用：编辑接口不受软删过滤影响
        Product patch = new Product();
        patch.setStatus("ACTIVE");
        productService.update(id, patch);
        assertThat(productService.get(id).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void 无引用供应商客户仓库软删后列表不可见() {
        Supplier supplier = new Supplier();
        supplier.setName("软删供应商-" + System.nanoTime());
        supplier.setStatus("ACTIVE");
        supplier.setFirstAuditStatus("APPROVED");
        Long sid = supplierService.create(supplier).getId();
        supplierService.delete(sid);
        assertThat(supplierRepository.findById(sid).orElseThrow().getStatus()).isEqualTo("INACTIVE");
        assertThat(supplierService.list(supplier.getName(), "ACTIVE", 1, 10).getRecords()).isEmpty();

        Customer customer = new Customer();
        customer.setName("软删客户-" + System.nanoTime());
        customer.setStatus("ACTIVE");
        customer.setFirstAuditStatus("APPROVED");
        Long cid = customerService.create(customer).getId();
        customerService.delete(cid);
        assertThat(customerRepository.findById(cid).orElseThrow().getStatus()).isEqualTo("INACTIVE");
        assertThat(customerService.list(customer.getName(), "ACTIVE", 1, 10).getRecords()).isEmpty();

        Warehouse warehouse = new Warehouse();
        warehouse.setName("软删仓库-" + System.nanoTime());
        warehouse.setTempType("ROOM_TEMP");
        warehouse.setStatus("ACTIVE");
        Long wid = warehouseService.create(warehouse).getId();
        warehouseService.delete(wid);
        assertThat(warehouseRepository.findById(wid).orElseThrow().getStatus()).isEqualTo("INACTIVE");
        assertThat(warehouseService.list(warehouse.getName(), "ACTIVE", 1, 10).getRecords()).isEmpty();
    }
}
