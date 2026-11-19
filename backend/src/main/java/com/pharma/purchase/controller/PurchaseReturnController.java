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
import com.pharma.purchase.dto.PurchaseReturnCreateRequest;
import com.pharma.purchase.dto.PurchaseReturnExportRow;
import com.pharma.purchase.dto.PurchaseReturnVO;
import com.pharma.purchase.service.PurchaseReturnService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * 采购退货：DRAFT → SUBMITTED → APPROVED（审批通过后扣批次库存 + 生成应付红冲）→ COMPLETED。
 */
@RestController
@RequestMapping("/api/purchase/returns")
public class PurchaseReturnController {

    private static final String VOID_PERMISSION = "purchase:return:void";

    private final PurchaseReturnService service;
    private final BatchOperationRunner batchRunner;
    private final ExportWriter exportWriter;

    public PurchaseReturnController(PurchaseReturnService service, BatchOperationRunner batchRunner,
                                    ExportWriter exportWriter) {
        this.service = service;
        this.batchRunner = batchRunner;
        this.exportWriter = exportWriter;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('purchase:return:view')")
    public ApiResponse<PageResult<PurchaseReturnVO>> list(@RequestParam(required = false) String keyword,
                                                          @RequestParam(required = false) Long supplierId,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, supplierId, status, page, size));
    }

    /** 列表导出（EasyExcel）：复用 keyword/supplierId 条件，不分页（上限 1 万行）。 */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('purchase:return:view')")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Long supplierId,
                       @RequestParam(required = false) String status,
                       HttpServletResponse response) throws IOException {
        ExportPayload<PurchaseReturnExportRow> payload = service.exportRows(keyword, supplierId, status);
        exportWriter.write(response, "采购退货", "采购退货单", "PURCHASE_RETURN",
                "keyword=" + keyword + ", supplierId=" + supplierId, PurchaseReturnExportRow.class, payload);
    }

    /** 采购退货详情：含明细、原入库单号与供应商名。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:return:view')")
    public ApiResponse<PurchaseReturnVO> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('purchase:return:create')")
    public ApiResponse<PurchaseReturnVO> create(@Valid @RequestBody PurchaseReturnCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    /** 编辑草稿/已驳回退货单（审批通过前不扣库存、不生成红字应付）。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:return:create')")
    public ApiResponse<PurchaseReturnVO> update(@PathVariable Long id,
                                                @Valid @RequestBody PurchaseReturnCreateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /** 提交审批：DRAFT / REJECTED -> SUBMITTED，进入审批中心待办。 */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('purchase:return:submit')")
    public ApiResponse<PurchaseReturnVO> submit(@PathVariable Long id) {
        return ApiResponse.ok(service.submit(id));
    }

    /** 批量提交审批：逐条独立事务，部分成功可表达。 */
    @PostMapping("/batch-submit")
    @PreAuthorize("hasAuthority('purchase:return:submit')")
    public ApiResponse<BatchResult> batchSubmit(@RequestBody BatchIdsRequest request) {
        return ApiResponse.ok(batchRunner.runSubmit("purchase_return", request.getIds(),
                id -> {
                    service.submit(id);
                    return id;
                }));
    }

    /** 采购退货作废红冲（P2-11）：库存回补 + 原红字应付联动作废。 */

    /** 采购退货WMS回传：审批下发 WMS 后由 WMS 回传实际数量，按实量执行并置 COMPLETED（幂等拒绝重复回传）。 */
    @PostMapping("/{id}/wms-callback")
    @PreAuthorize("hasAuthority('purchase:return:wms-callback')")
    public ApiResponse<PurchaseReturnVO> wmsCallback(@PathVariable Long id,
                                         @RequestBody(required = false) WmsCallbackRequest request) {
        return ApiResponse.ok(service.wmsCallback(id, request));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('purchase:return:void')")
    public ApiResponse<PurchaseReturnVO> voidReturn(@PathVariable Long id,
                                                    @RequestBody(required = false) VoidRequest request) {
        return ApiResponse.ok(service.voidReturn(id, request == null ? null : request.reason()));
    }

    /** 批量作废：逐条独立事务，部分成功可表达（已作废的条目单独失败）。 */
    @PostMapping("/batch-void")
    @PreAuthorize("hasAuthority('purchase:return:void')")
    public ApiResponse<BatchResult> batchVoid(@RequestBody BatchVoidRequest request) {
        return ApiResponse.ok(batchRunner.runVoid("purchase_return", VOID_PERMISSION,
                request.getIds(), request.getReason(),
                id -> {
                    service.voidReturn(id, request.getReason());
                    return id;
                }));
    }
}
