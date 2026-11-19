package com.pharma.inventory.controller;

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
import com.pharma.inventory.dto.StockTransferCreateRequest;
import com.pharma.inventory.dto.StockTransferExportRow;
import com.pharma.inventory.dto.StockTransferVO;
import com.pharma.inventory.service.StockTransferService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * 仓库调拨：DRAFT → SUBMITTED → APPROVED（审批通过后一事务完成调出扣减 + 调入 upsert）→ COMPLETED。
 */
@RestController
@RequestMapping("/api/inventory/transfers")
public class StockTransferController {

    private static final String VOID_PERMISSION = "inventory:transfer:void";

    private final StockTransferService service;
    private final BatchOperationRunner batchRunner;
    private final ExportWriter exportWriter;

    public StockTransferController(StockTransferService service, BatchOperationRunner batchRunner,
                                   ExportWriter exportWriter) {
        this.service = service;
        this.batchRunner = batchRunner;
        this.exportWriter = exportWriter;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory:transfer:view')")
    public ApiResponse<PageResult<StockTransferVO>> list(@RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    /** 列表导出（EasyExcel）：复用 keyword 条件，不分页（上限 1 万行）。 */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('inventory:transfer:view')")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String status,
                       HttpServletResponse response) throws IOException {
        ExportPayload<StockTransferExportRow> payload = service.exportRows(keyword, status);
        exportWriter.write(response, "仓库调拨", "仓库调拨单", "STOCK_TRANSFER",
                "keyword=" + keyword, StockTransferExportRow.class, payload);
    }

    /** 调拨单详情：含明细与调出/调入仓名称。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory:transfer:view')")
    public ApiResponse<StockTransferVO> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('inventory:transfer:create')")
    public ApiResponse<StockTransferVO> create(@Valid @RequestBody StockTransferCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    /** 编辑草稿/已驳回调拨单（审批通过前不执行移库）。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory:transfer:create')")
    public ApiResponse<StockTransferVO> update(@PathVariable Long id,
                                               @Valid @RequestBody StockTransferCreateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /** 提交审批：DRAFT / REJECTED -> SUBMITTED，进入审批中心待办。 */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('inventory:transfer:submit')")
    public ApiResponse<StockTransferVO> submit(@PathVariable Long id) {
        return ApiResponse.ok(service.submit(id));
    }

    /** 批量提交审批：逐条独立事务，部分成功可表达。 */
    @PostMapping("/batch-submit")
    @PreAuthorize("hasAuthority('inventory:transfer:submit')")
    public ApiResponse<BatchResult> batchSubmit(@RequestBody BatchIdsRequest request) {
        return ApiResponse.ok(batchRunner.runSubmit("stock_transfer", request.getIds(),
                id -> {
                    service.submit(id);
                    return id;
                }));
    }

    /** 调拨作废红冲（P2-11）：反向流水 + 状态 VOID，不物理删除。 */

    /** 仓库调拨WMS回传：审批下发 WMS 后由 WMS 回传实际数量，按实量执行并置 COMPLETED（幂等拒绝重复回传）。 */
    @PostMapping("/{id}/wms-callback")
    @PreAuthorize("hasAuthority('inventory:transfer:wms-callback')")
    public ApiResponse<StockTransferVO> wmsCallback(@PathVariable Long id,
                                         @RequestBody(required = false) WmsCallbackRequest request) {
        return ApiResponse.ok(service.wmsCallback(id, request));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('inventory:transfer:void')")
    public ApiResponse<StockTransferVO> voidTransfer(@PathVariable Long id,
                                                     @RequestBody(required = false) VoidRequest request) {
        return ApiResponse.ok(service.voidTransfer(id, request == null ? null : request.reason()));
    }

    /** 批量作废：逐条独立事务，部分成功可表达（已作废/调入仓库存不足的条目单独失败）。 */
    @PostMapping("/batch-void")
    @PreAuthorize("hasAuthority('inventory:transfer:void')")
    public ApiResponse<BatchResult> batchVoid(@RequestBody BatchVoidRequest request) {
        return ApiResponse.ok(batchRunner.runVoid("stock_transfer", VOID_PERMISSION,
                request.getIds(), request.getReason(),
                id -> {
                    service.voidTransfer(id, request.getReason());
                    return id;
                }));
    }
}
