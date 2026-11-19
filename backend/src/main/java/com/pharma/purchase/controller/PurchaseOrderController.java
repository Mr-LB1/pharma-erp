package com.pharma.purchase.controller;

import com.pharma.common.ApiResponse;
import com.pharma.common.PageResult;
import com.pharma.common.VoidRequest;
import com.pharma.common.batch.BatchIdsRequest;
import com.pharma.common.batch.BatchOperationRunner;
import com.pharma.common.batch.BatchResult;
import com.pharma.common.batch.BatchVoidRequest;
import com.pharma.common.export.ExportPayload;
import com.pharma.common.export.ExportWriter;
import com.pharma.purchase.dto.PurchaseOrderCreateRequest;
import com.pharma.purchase.dto.PurchaseOrderExportRow;
import com.pharma.purchase.dto.PurchaseOrderVO;
import com.pharma.purchase.service.PurchaseOrderService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/purchase/orders")
public class PurchaseOrderController {

    private final PurchaseOrderService service;
    private final BatchOperationRunner batchRunner;
    private final ExportWriter exportWriter;

    public PurchaseOrderController(PurchaseOrderService service, BatchOperationRunner batchRunner,
                                   ExportWriter exportWriter) {
        this.service = service;
        this.batchRunner = batchRunner;
        this.exportWriter = exportWriter;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('purchase:order:create')")
    public ApiResponse<PurchaseOrderVO> create(@Valid @RequestBody PurchaseOrderCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('purchase:order:view')")
    public ApiResponse<PageResult<PurchaseOrderVO>> list(@RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    /** 列表导出（EasyExcel）：复用列表的 keyword/status 条件与数据权限，不分页（上限 1 万行）。 */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('purchase:order:view')")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String status,
                       HttpServletResponse response) throws IOException {
        ExportPayload<PurchaseOrderExportRow> payload = service.exportRows(keyword, status);
        exportWriter.write(response, "采购订单", "采购订单", "PURCHASE_ORDER",
                "keyword=" + keyword + ", status=" + status, PurchaseOrderExportRow.class, payload);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:order:view')")
    public ApiResponse<PurchaseOrderVO> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('purchase:order:submit')")
    public ApiResponse<PurchaseOrderVO> submit(@PathVariable Long id) {
        return ApiResponse.ok(service.submit(id));
    }

    /** 草稿编辑（仅 DRAFT 可改，整单重建明细）。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:order:update')")
    public ApiResponse<PurchaseOrderVO> update(@PathVariable Long id,
                                               @Valid @RequestBody PurchaseOrderCreateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /** 驳回后重新提交（REJECTED -> DRAFT -> SUBMITTED）。 */
    @PostMapping("/{id}/resubmit")
    @PreAuthorize("hasAuthority('purchase:order:submit')")
    public ApiResponse<PurchaseOrderVO> resubmit(@PathVariable Long id) {
        return ApiResponse.ok(service.resubmit(id));
    }

    /** 作废订单（DRAFT / SUBMITTED -> VOID 终态），可带 {reason} 作废原因留痕。 */
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('purchase:order:void')")
    public ApiResponse<PurchaseOrderVO> voidOrder(@PathVariable Long id,
                                                  @RequestBody(required = false) VoidRequest request) {
        return ApiResponse.ok(service.voidOrder(id, request == null ? null : request.reason()));
    }

    /** 批量提交（§13.1）：逐条执行 submit，每条独立事务。 */
    @PostMapping("/batch-submit")
    @PreAuthorize("hasAuthority('purchase:order:submit')")
    public ApiResponse<BatchResult> batchSubmit(@RequestBody BatchIdsRequest request) {
        return ApiResponse.ok(batchRunner.runSubmit("purchase_order", request.getIds(),
                id -> {
                    service.submit(id);
                    return id;
                }));
    }

    /** 批量作废：逐条独立事务，部分成功可表达（仅 DRAFT/SUBMITTED 可作废）。 */
    @PostMapping("/batch-void")
    @PreAuthorize("hasAuthority('purchase:order:void')")
    public ApiResponse<BatchResult> batchVoid(@RequestBody BatchVoidRequest request) {
        return ApiResponse.ok(batchRunner.runVoid("purchase_order", "purchase:order:void",
                request.getIds(), request.getReason(),
                id -> {
                    service.voidOrder(id, request.getReason());
                    return id;
                }));
    }
}
