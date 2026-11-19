package com.pharma.sales.controller;

import com.pharma.common.ApiResponse;
import com.pharma.common.PageResult;
import com.pharma.common.VoidRequest;
import com.pharma.common.batch.BatchOperationRunner;
import com.pharma.common.batch.BatchResult;
import com.pharma.common.batch.BatchVoidRequest;
import com.pharma.common.export.ExportPayload;
import com.pharma.common.export.ExportWriter;
import com.pharma.sales.dto.SalesReturnCreateRequest;
import com.pharma.sales.dto.SalesReturnExportRow;
import com.pharma.sales.dto.SalesReturnReceiveRequest;
import com.pharma.sales.dto.SalesReturnVO;
import com.pharma.sales.service.SalesReturnService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * 销售退货：创建 → 提审 → 审批（/api/approvals/SALES_RETURN/{id}/approve）→ 验收。
 */
@RestController
@RequestMapping("/api/sales/returns")
public class SalesReturnController {

    private static final String VOID_PERMISSION = "sales:return:void";

    private final SalesReturnService service;
    private final BatchOperationRunner batchRunner;
    private final ExportWriter exportWriter;

    public SalesReturnController(SalesReturnService service, BatchOperationRunner batchRunner,
                                 ExportWriter exportWriter) {
        this.service = service;
        this.batchRunner = batchRunner;
        this.exportWriter = exportWriter;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('sales:return:view')")
    public ApiResponse<PageResult<SalesReturnVO>> list(@RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    /** 列表导出（EasyExcel）：复用 keyword/status 条件，不分页（上限 1 万行）。 */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('sales:return:view')")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String status,
                       HttpServletResponse response) throws IOException {
        ExportPayload<SalesReturnExportRow> payload = service.exportRows(keyword, status);
        exportWriter.write(response, "销售退货", "销售退货单", "SALES_RETURN",
                "keyword=" + keyword + ", status=" + status, SalesReturnExportRow.class, payload);
    }

    /** 销售退货详情：含明细、原出库单号与客户名。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('sales:return:view')")
    public ApiResponse<SalesReturnVO> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('sales:return:create')")
    public ApiResponse<SalesReturnVO> create(@Valid @RequestBody SalesReturnCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('sales:return:create')")
    public ApiResponse<SalesReturnVO> submit(@PathVariable Long id) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('sales:return:receive')")
    public ApiResponse<SalesReturnVO> receive(@PathVariable Long id,
                                              @Valid @RequestBody(required = false) SalesReturnReceiveRequest request) {
        return ApiResponse.ok(service.receive(id, request));
    }

    /** 销售退货作废（批次十三）：已验收单调回库存并冲回红字应收。 */
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('sales:return:void')")
    public ApiResponse<SalesReturnVO> voidReturn(@PathVariable Long id,
                                                 @RequestBody(required = false) VoidRequest request) {
        return ApiResponse.ok(service.voidReturn(id, request == null ? null : request.reason()));
    }

    /** 批量作废：逐条独立事务，部分成功可表达（待审批/已作废的条目单独失败）。 */
    @PostMapping("/batch-void")
    @PreAuthorize("hasAuthority('sales:return:void')")
    public ApiResponse<BatchResult> batchVoid(@RequestBody BatchVoidRequest request) {
        return ApiResponse.ok(batchRunner.runVoid("sales_return", VOID_PERMISSION,
                request.getIds(), request.getReason(),
                id -> {
                    service.voidReturn(id, request.getReason());
                    return id;
                }));
    }
}
