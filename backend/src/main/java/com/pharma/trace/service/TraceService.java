package com.pharma.trace.service;

import com.pharma.base.entity.Customer;
import com.pharma.base.entity.Supplier;
import com.pharma.base.repository.CustomerRepository;
import com.pharma.base.repository.SupplierRepository;
import com.pharma.common.BusinessException;
import com.pharma.inventory.entity.LotTrace;
import com.pharma.inventory.repository.LotTraceRepository;
import com.pharma.inventory.service.InventoryLedgerService;
import com.pharma.purchase.entity.PurchaseOrder;
import com.pharma.purchase.entity.PurchaseReceive;
import com.pharma.purchase.entity.PurchaseReceiveItem;
import com.pharma.purchase.repository.PurchaseOrderRepository;
import com.pharma.purchase.repository.PurchaseReceiveItemRepository;
import com.pharma.purchase.repository.PurchaseReceiveRepository;
import com.pharma.sales.entity.SalesDelivery;
import com.pharma.sales.entity.SalesDeliveryItem;
import com.pharma.sales.entity.SalesOrder;
import com.pharma.sales.repository.SalesDeliveryItemRepository;
import com.pharma.sales.repository.SalesDeliveryRepository;
import com.pharma.sales.repository.SalesOrderRepository;
import com.pharma.trace.dto.TraceInbound;
import com.pharma.trace.dto.TraceOutbound;
import com.pharma.trace.dto.TraceResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 批次追溯服务（R7）。
 * 正向：批次 -> 采购入库明细 -> 销售出库明细 -> 销售订单 -> 客户
 * 逆向：批次 -> 销售出库 -> 采购入库 -> 供应商
 * 均基于 lot_trace + 关联表返回结构化链路（inbounds + outbounds 完整链路）。
 */
@Service
public class TraceService {

    private final LotTraceRepository lotTraceRepository;
    private final PurchaseReceiveItemRepository receiveItemRepository;
    private final PurchaseReceiveRepository receiveRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SalesDeliveryItemRepository deliveryItemRepository;
    private final SalesDeliveryRepository deliveryRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SupplierRepository supplierRepository;
    private final CustomerRepository customerRepository;

    public TraceService(LotTraceRepository lotTraceRepository,
                        PurchaseReceiveItemRepository receiveItemRepository,
                        PurchaseReceiveRepository receiveRepository,
                        PurchaseOrderRepository purchaseOrderRepository,
                        SalesDeliveryItemRepository deliveryItemRepository,
                        SalesDeliveryRepository deliveryRepository,
                        SalesOrderRepository salesOrderRepository,
                        SupplierRepository supplierRepository,
                        CustomerRepository customerRepository) {
        this.lotTraceRepository = lotTraceRepository;
        this.receiveItemRepository = receiveItemRepository;
        this.receiveRepository = receiveRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.deliveryItemRepository = deliveryItemRepository;
        this.deliveryRepository = deliveryRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.supplierRepository = supplierRepository;
        this.customerRepository = customerRepository;
    }

    public TraceResult forward(Long productId, String batchNo) {
        return trace(productId, batchNo);
    }

    public TraceResult backward(Long productId, String batchNo) {
        return trace(productId, batchNo);
    }

    private TraceResult trace(Long productId, String batchNo) {
        if (productId == null) {
            throw BusinessException.badRequest("商品不能为空");
        }
        if (batchNo == null || batchNo.isBlank()) {
            throw BusinessException.badRequest("批号不能为空");
        }
        List<LotTrace> traces = lotTraceRepository.findByProductIdAndBatchNoOrderByIdAsc(productId, batchNo);

        // 入库链路：PURCHASE_RECEIVE_ITEM -> STOCK
        List<Long> receiveItemIds = traces.stream()
                .filter(t -> InventoryLedgerService.TRACE_PURCHASE_RECEIVE_ITEM.equals(t.getSourceType()))
                .map(LotTrace::getSourceId)
                .distinct()
                .toList();
        // 出库链路：STOCK -> SALES_DELIVERY_ITEM
        List<Long> deliveryItemIds = traces.stream()
                .filter(t -> InventoryLedgerService.TRACE_SALES_DELIVERY_ITEM.equals(t.getTargetType()))
                .map(LotTrace::getTargetId)
                .distinct()
                .toList();

        return new TraceResult(productId, batchNo, buildInbounds(receiveItemIds), buildOutbounds(deliveryItemIds));
    }

    private List<TraceInbound> buildInbounds(List<Long> receiveItemIds) {
        if (receiveItemIds.isEmpty()) {
            return List.of();
        }
        Map<Long, PurchaseReceiveItem> itemMap = receiveItemRepository.findAllById(receiveItemIds).stream()
                .collect(Collectors.toMap(PurchaseReceiveItem::getId, Function.identity()));

        Set<Long> receiveIds = itemMap.values().stream().map(PurchaseReceiveItem::getReceiveId).collect(Collectors.toSet());
        Map<Long, PurchaseReceive> receiveMap = receiveRepository.findAllById(receiveIds).stream()
                .collect(Collectors.toMap(PurchaseReceive::getId, Function.identity()));

        Set<Long> orderIds = receiveMap.values().stream().map(PurchaseReceive::getOrderId).collect(Collectors.toSet());
        Map<Long, PurchaseOrder> orderMap = purchaseOrderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));

        Set<Long> supplierIds = orderMap.values().stream().map(PurchaseOrder::getSupplierId).collect(Collectors.toSet());
        Map<Long, Supplier> supplierMap = supplierRepository.findAllById(supplierIds).stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity()));

        List<TraceInbound> result = new ArrayList<>();
        for (Long rid : receiveItemIds) {
            PurchaseReceiveItem ri = itemMap.get(rid);
            if (ri == null) {
                continue;
            }
            PurchaseReceive r = receiveMap.get(ri.getReceiveId());
            PurchaseOrder o = r == null ? null : orderMap.get(r.getOrderId());
            Supplier s = o == null ? null : supplierMap.get(o.getSupplierId());
            result.add(new TraceInbound(
                    ri.getId(),
                    r == null ? null : r.getId(),
                    r == null ? null : r.getReceiveNo(),
                    r == null ? null : r.getReceiveDate(),
                    ri.getQuantity(),
                    ri.getProduceDate(),
                    ri.getExpireDate(),
                    ri.getUnitCost(),
                    ri.getInspectionNo(),
                    o == null ? null : o.getId(),
                    o == null ? null : o.getOrderNo(),
                    s == null ? null : s.getId(),
                    s == null ? null : s.getName(),
                    r == null ? null : r.getStatus()));
        }
        return result;
    }

    private List<TraceOutbound> buildOutbounds(List<Long> deliveryItemIds) {
        if (deliveryItemIds.isEmpty()) {
            return List.of();
        }
        Map<Long, SalesDeliveryItem> itemMap = deliveryItemRepository.findAllById(deliveryItemIds).stream()
                .collect(Collectors.toMap(SalesDeliveryItem::getId, Function.identity()));

        Set<Long> deliveryIds = itemMap.values().stream().map(SalesDeliveryItem::getDeliveryId).collect(Collectors.toSet());
        Map<Long, SalesDelivery> deliveryMap = deliveryRepository.findAllById(deliveryIds).stream()
                .collect(Collectors.toMap(SalesDelivery::getId, Function.identity()));

        Set<Long> orderIds = deliveryMap.values().stream().map(SalesDelivery::getOrderId).collect(Collectors.toSet());
        Map<Long, SalesOrder> orderMap = salesOrderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(SalesOrder::getId, Function.identity()));

        Set<Long> customerIds = orderMap.values().stream().map(SalesOrder::getCustomerId).collect(Collectors.toSet());
        Map<Long, Customer> customerMap = customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));

        List<TraceOutbound> result = new ArrayList<>();
        for (Long did : deliveryItemIds) {
            SalesDeliveryItem di = itemMap.get(did);
            if (di == null) {
                continue;
            }
            SalesDelivery d = deliveryMap.get(di.getDeliveryId());
            SalesOrder o = d == null ? null : orderMap.get(d.getOrderId());
            Customer c = o == null ? null : customerMap.get(o.getCustomerId());
            result.add(new TraceOutbound(
                    di.getId(),
                    d == null ? null : d.getId(),
                    d == null ? null : d.getDeliveryNo(),
                    d == null ? null : d.getDeliveryDate(),
                    di.getQuantity(),
                    di.getUnitCost(),
                    o == null ? null : o.getId(),
                    o == null ? null : o.getOrderNo(),
                    c == null ? null : c.getId(),
                    c == null ? null : c.getName(),
                    d == null ? null : d.getStatus()));
        }
        return result;
    }
}
