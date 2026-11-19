package com.pharma.inventory;

import com.pharma.base.entity.Product;
import com.pharma.base.entity.Warehouse;
import com.pharma.base.repository.ProductRepository;
import com.pharma.base.repository.WarehouseRepository;
import com.pharma.inventory.entity.Stock;
import com.pharma.inventory.repository.StockRepository;
import com.pharma.inventory.service.InventoryLedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-20 并发首建：多个线程同时入库「同商品 + 同批号 + 同仓」的新批次，
 * 不得因唯一约束冲突抛 500；最终库存 = 各次入库之和，且只有一条批次记录。
 * 注意：本测试需要真实并发提交，故**不使用**回滚事务（自行清理数据）。
 */
@SpringBootTest
class ConcurrentUpsertTest {

    private static final int THREADS = 8;

    @Autowired
    private InventoryLedgerService ledger;
    @Autowired
    private StockRepository stockRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private WarehouseRepository warehouseRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long productId;
    private Long warehouseId;
    private String batchNo;

    @BeforeEach
    void setUp() {
        Warehouse warehouse = new Warehouse();
        warehouse.setName("并发测试仓-" + System.nanoTime());
        warehouse.setTempType("ROOM_TEMP");
        warehouse.setStatus("ACTIVE");
        warehouseId = warehouseRepository.save(warehouse).getId();

        Product product = new Product();
        product.setCode("CU-" + System.nanoTime());
        product.setGenericName("并发测试药品");
        product.setStatus("ACTIVE");
        product.setFirstAuditStatus("APPROVED");
        productId = productRepository.save(product).getId();

        batchNo = "CU-B-" + System.nanoTime();
    }

    @AfterEach
    void cleanUp() {
        stockRepository.findByProductIdAndBatchNo(productId, batchNo).forEach(stockRepository::delete);
        productRepository.deleteById(productId);
        warehouseRepository.deleteById(warehouseId);
    }

    @Test
    void 并发首建同批次不报错且数量准确() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    tx.executeWithoutResult(status -> ledger.upsertStock(productId, batchNo,
                            LocalDate.now().minusDays(10), LocalDate.now().plusDays(365),
                            warehouseId, new BigDecimal("5"), new BigDecimal("10")));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<Stock> stocks = stockRepository.findByProductIdAndBatchNo(productId, batchNo);
        assertThat(stocks).hasSize(1);
        assertThat(stocks.get(0).getQuantity()).isEqualByComparingTo("40");
    }
}
