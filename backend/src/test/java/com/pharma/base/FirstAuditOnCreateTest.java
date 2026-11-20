package com.pharma.base;

import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Product;
import com.pharma.base.entity.Supplier;
import com.pharma.base.service.CustomerService;
import com.pharma.base.service.ProductService;
import com.pharma.base.service.SupplierService;
import com.pharma.inventory.AbstractInventoryTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-4 基础资料新增首营强制 PENDING 回归测试。
 * <p>create 时即使客户端传入 firstAuditStatus=APPROVED，也必须以 PENDING 落库；
 * 首营状态只能经 FirstAuditService 审核流转。</p>
 */
class FirstAuditOnCreateTest extends AbstractInventoryTest {

    /** 商品服务：验证 create 强制首营 PENDING。 */
    @Autowired
    private ProductService productService;

    /** 供应商服务：验证 create 强制首营 PENDING。 */
    @Autowired
    private SupplierService supplierService;

    /** 客户服务：验证 create 强制首营 PENDING。 */
    @Autowired
    private CustomerService customerService;

    /** 实体管理器：flush + clear 后重新从数据库加载，验证真实落库值。 */
    @Autowired
    private EntityManager entityManager;

    /**
     * 新增商品时传 APPROVED，落库仍为 PENDING。
     */
    @Test
    void 新增商品传APPROVED落库仍为PENDING() {
        // 1. 构造带 APPROVED 的商品，模拟客户端越权传值
        Product product = new Product();
        product.setCode("FA-P-" + System.nanoTime());
        product.setGenericName("首营强制商品");
        product.setFirstAuditStatus("APPROVED");
        product.setStatus("ACTIVE");

        // 2. 经 Service 新增，返回值应被强制为 PENDING
        Product created = productService.create(product);
        assertThat(created.getFirstAuditStatus()).isEqualTo("PENDING");

        // 3. flush + clear 后从数据库重新加载，确认落库值同样是 PENDING
        entityManager.flush();
        entityManager.clear();
        assertThat(productRepository.findById(created.getId()).orElseThrow().getFirstAuditStatus())
                .isEqualTo("PENDING");
    }

    /**
     * 新增供应商时传 APPROVED，落库仍为 PENDING。
     */
    @Test
    void 新增供应商传APPROVED落库仍为PENDING() {
        // 1. 构造带 APPROVED 的供应商，模拟客户端越权传值
        Supplier supplier = new Supplier();
        supplier.setName("首营强制供应商-" + System.nanoTime());
        supplier.setFirstAuditStatus("APPROVED");
        supplier.setStatus("ACTIVE");

        // 2. 经 Service 新增，返回值应被强制为 PENDING
        Supplier created = supplierService.create(supplier);
        assertThat(created.getFirstAuditStatus()).isEqualTo("PENDING");

        // 3. flush + clear 后从数据库重新加载，确认落库值同样是 PENDING
        entityManager.flush();
        entityManager.clear();
        assertThat(supplierRepository.findById(created.getId()).orElseThrow().getFirstAuditStatus())
                .isEqualTo("PENDING");
    }

    /**
     * 新增客户时传 APPROVED，落库仍为 PENDING。
     */
    @Test
    void 新增客户传APPROVED落库仍为PENDING() {
        // 1. 构造带 APPROVED 的客户，模拟客户端越权传值
        Customer customer = new Customer();
        customer.setName("首营强制客户-" + System.nanoTime());
        customer.setFirstAuditStatus("APPROVED");
        customer.setStatus("ACTIVE");

        // 2. 经 Service 新增，返回值应被强制为 PENDING
        Customer created = customerService.create(customer);
        assertThat(created.getFirstAuditStatus()).isEqualTo("PENDING");

        // 3. flush + clear 后从数据库重新加载，确认落库值同样是 PENDING
        entityManager.flush();
        entityManager.clear();
        assertThat(customerRepository.findById(created.getId()).orElseThrow().getFirstAuditStatus())
                .isEqualTo("PENDING");
    }
}
