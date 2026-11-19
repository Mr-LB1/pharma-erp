package com.pharma.compliance;

import com.pharma.inventory.AbstractInventoryTest;
import com.pharma.inventory.repository.LotTraceRepository;
import com.pharma.inventory.repository.StockTransactionRepository;
import com.pharma.quality.service.FirstAuditService;
import com.pharma.trace.service.TraceService;
import com.pharma.inventory.service.StocktakeService;
import com.pharma.purchase.repository.PurchaseReceiveRepository;
import com.pharma.sales.repository.SalesDeliveryRepository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M3 合规规则测试基类：复用库存测试夹具（APPROVED 商品/供应商/客户 + 仓库），并注入合规域服务。
 */
public abstract class AbstractComplianceTest extends AbstractInventoryTest {

    @Autowired
    protected FirstAuditService firstAuditService;
    @Autowired
    protected TraceService traceService;
    @Autowired
    protected StocktakeService stocktakeService;
    @Autowired
    protected LotTraceRepository lotTraceRepository;
    @Autowired
    protected StockTransactionRepository stockTransactionRepository;
    @Autowired
    protected PurchaseReceiveRepository purchaseReceiveRepository;
    @Autowired
    protected SalesDeliveryRepository salesDeliveryRepository;
}
