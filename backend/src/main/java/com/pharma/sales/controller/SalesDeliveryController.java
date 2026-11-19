package com.pharma.sales.controller;

import com.pharma.common.ApiResponse;
import com.pharma.common.WmsCallbackRequest;
import com.pharma.common.PageResult;
import com.pharma.common.VoidRequest;
import com.pharma.common.batch.BatchOperationRunner;
import com.pharma.common.batch.BatchIdsRequest;
import com.pharma.common.batch.BatchResult;
import com.pharma.common.batch.BatchVoidRequest;
import com.pharma.common.export.ExportPayload;
import com.pharma.common.export.ExportWriter;
import com.pharma.sales.dto.SalesDeliveryCreateRequest;
import com.pharma.sales.dto.SalesDeliveryExportRow;
import com.pharma.sales.dto.SalesDeliveryVO;
import com.pharma.sales.service.SalesDeliveryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/sales/deliveries")
public class SalesDeliveryController {

    private static final String VOID_PERMISSION = "sales:delivery:void";

    private final SalesDeliveryService service;
    private final BatchOperationRunner batchRunner;
    private final ExportWriter exportWriter;

    public SalesDeliveryController(SalesDeliveryService service, BatchOperationRunner batchRunner,
                                   ExportWriter exportWriter) {
        this.service = service;
        this.batchRunner = batchRunner;
        this.exportWriter = exportWriter;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('sales:delivery:create')")
    public ApiResponse<SalesDeliveryVO> create(@Valid @RequestBody SalesDeliveryCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    /** 编辑草稿/已驳回出库单（审批通过前不锁批、不扣库存、不生成应收）。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('sales:delivery:create')")
    public ApiResponse<SalesDeliveryVO> update(@PathVariable Long id,
                                               @Valid @RequestBody SalesDeliveryCreateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /** 提交审批：DRAFT / REJECTED -> SUBMITTED，进入审批中心待办。 */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('sales:delivery:submit')")
    public ApiResponse<SalesDeliveryVO> submit(@PathVariable Long id) {
        return ApiResponse.ok(service.submit(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('sales:delivery:view')")
    public ApiResponse<PageResult<SalesDeliveryVO>> list(@RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    /** 列表导出（EasyExcel）：复用 keyword 条件，不分页（上限 1 万行）。 */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('sales:delivery:view')")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String status,
                       HttpServletResponse response) throws IOException {
        ExportPayload<SalesDeliveryExportRow> payload = service.exportRows(keyword, status);
        exportWriter.write(response, "销售出库", "销售出库单", "SALES_DELIVERY",
                "keyword=" + keyword, SalesDeliveryExportRow.class, payload);
    }

    /** 出库单详情：含明细 + 客户名/订单号。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('sales:delivery:view')")
    public ApiResponse<SalesDeliveryVO> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    /** 批量提交审批：逐条独立事务，部分成功可表达。 */
    @PostMapping("/batch-submit")
    @PreAuthorize("hasAuthority('sales:delivery:submit')")
    public ApiResponse<BatchResult> batchSubmit(@RequestBody BatchIdsRequest request) {
        return ApiResponse.ok(batchRunner.runSubmit("sales_delivery", request.getIds(),
                id -> {
                    service.submit(id);
                    return id;
                }));
    }


    /** 销售出库WMS回传：审批下发 WMS 后由 WMS 回传实际数量，按实量执行并置 COMPLETED（幂等拒绝重复回传）。 */
    @PostMapping("/{id}/wms-callback")
    @PreAuthorize("hasAuthority('sales:delivery:wms-callback')")
    public ApiResponse<SalesDeliveryVO> wmsCallback(@PathVariable Long id,
                                         @RequestBody(required = false) WmsCallbackRequest request) {
        return ApiResponse.ok(service.wmsCallback(id, request));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('sales:delivery:void')")
    public ApiResponse<SalesDeliveryVO> voidDelivery(@PathVariable Long id,
                                                     @RequestBody(required = false) VoidRequest request) {
        return ApiResponse.ok(service.voidDelivery(id, request == null ? null : request.reason()));
    }

    /** 批量作废：逐条独立事务，部分成功可表达（状态不对/已作废的条目单独失败）。 */
    @PostMapping("/batch-void")
    @PreAuthorize("hasAuthority('sales:delivery:void')")
    public ApiResponse<BatchResult> batchVoid(@RequestBody BatchVoidRequest request) {
        return ApiResponse.ok(batchRunner.runVoid("sales_delivery", VOID_PERMISSION,
                request.getIds(), request.getReason(),
                id -> {
                    service.voidDelivery(id, request.getReason());
                    return id;
                }));
    }
}
