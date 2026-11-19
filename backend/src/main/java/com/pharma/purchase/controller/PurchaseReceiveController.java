package com.pharma.purchase.controller;

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
import com.pharma.purchase.dto.PurchaseReceiveCreateRequest;
import com.pharma.purchase.dto.PurchaseReceiveExportRow;
import com.pharma.purchase.dto.PurchaseReceiveVO;
import com.pharma.purchase.service.PurchaseReceiveService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/purchase/receives")
public class PurchaseReceiveController {

    private static final String VOID_PERMISSION = "purchase:receive:void";

    private final PurchaseReceiveService service;
    private final BatchOperationRunner batchRunner;
    private final ExportWriter exportWriter;

    public PurchaseReceiveController(PurchaseReceiveService service, BatchOperationRunner batchRunner,
                                     ExportWriter exportWriter) {
        this.service = service;
        this.batchRunner = batchRunner;
        this.exportWriter = exportWriter;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('purchase:receive:create')")
    public ApiResponse<PurchaseReceiveVO> create(@Valid @RequestBody PurchaseReceiveCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    /** 编辑草稿/已驳回入库单（整单重建明细；审批通过前不动库存与应付）。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:receive:create')")
    public ApiResponse<PurchaseReceiveVO> update(@PathVariable Long id,
                                                 @Valid @RequestBody PurchaseReceiveCreateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /** 提交审批：DRAFT / REJECTED -> SUBMITTED，进入审批中心待办。 */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('purchase:receive:submit')")
    public ApiResponse<PurchaseReceiveVO> submit(@PathVariable Long id) {
        return ApiResponse.ok(service.submit(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('purchase:receive:view')")
    public ApiResponse<PageResult<PurchaseReceiveVO>> list(@RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    /** 列表导出（EasyExcel）：复用 keyword 条件，不分页（上限 1 万行）。 */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('purchase:receive:view')")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String status,
                       HttpServletResponse response) throws IOException {
        ExportPayload<PurchaseReceiveExportRow> payload = service.exportRows(keyword, status);
        exportWriter.write(response, "采购入库", "采购入库单", "PURCHASE_RECEIVE",
                "keyword=" + keyword, PurchaseReceiveExportRow.class, payload);
    }

    /** 入库单详情：含明细 + 供应商名/订单号。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:receive:view')")
    public ApiResponse<PurchaseReceiveVO> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    /** 批量提交审批：逐条独立事务，部分成功可表达。 */
    @PostMapping("/batch-submit")
    @PreAuthorize("hasAuthority('purchase:receive:submit')")
    public ApiResponse<BatchResult> batchSubmit(@RequestBody BatchIdsRequest request) {
        return ApiResponse.ok(batchRunner.runSubmit("purchase_receive", request.getIds(),
                id -> {
                    service.submit(id);
                    return id;
                }));
    }


    /** 采购入库WMS回传：审批下发 WMS 后由 WMS 回传实际数量，按实量执行并置 COMPLETED（幂等拒绝重复回传）。 */
    @PostMapping("/{id}/wms-callback")
    @PreAuthorize("hasAuthority('purchase:receive:wms-callback')")
    public ApiResponse<PurchaseReceiveVO> wmsCallback(@PathVariable Long id,
                                         @RequestBody(required = false) WmsCallbackRequest request) {
        return ApiResponse.ok(service.wmsCallback(id, request));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('purchase:receive:void')")
    public ApiResponse<PurchaseReceiveVO> voidReceive(@PathVariable Long id,
                                                      @RequestBody(required = false) VoidRequest request) {
        return ApiResponse.ok(service.voidReceive(id, request == null ? null : request.reason()));
    }

    /** 批量作废：逐条独立事务，部分成功可表达（状态不对/已作废的条目单独失败）。 */
    @PostMapping("/batch-void")
    @PreAuthorize("hasAuthority('purchase:receive:void')")
    public ApiResponse<BatchResult> batchVoid(@RequestBody BatchVoidRequest request) {
        return ApiResponse.ok(batchRunner.runVoid("purchase_receive", VOID_PERMISSION,
                request.getIds(), request.getReason(),
                id -> {
                    service.voidReceive(id, request.getReason());
                    return id;
                }));
    }
}
