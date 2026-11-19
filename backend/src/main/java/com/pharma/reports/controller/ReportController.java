package com.pharma.reports.controller;

import com.alibaba.excel.EasyExcel;
import com.pharma.common.ApiResponse;
import com.pharma.common.PageResult;
import com.pharma.common.SecurityUtils;
import com.pharma.reports.dto.DashboardVO;
import com.pharma.reports.dto.GrossProfitExportRow;
import com.pharma.reports.dto.GrossProfitVO;
import com.pharma.reports.dto.InventoryBalanceVO;
import com.pharma.reports.dto.NearExpiryExportRow;
import com.pharma.reports.dto.NearExpiryItemVO;
import com.pharma.reports.dto.StockSummaryExportRow;
import com.pharma.reports.dto.StockSummaryVO;
import com.pharma.reports.service.ReportService;
import com.pharma.system.service.AuditLogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService service;
    private final AuditLogService auditLogService;

    public ReportController(ReportService service, AuditLogService auditLogService) {
        this.service = service;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/stock-summary")
    @PreAuthorize("hasAuthority('report:view')")
    public ApiResponse<PageResult<StockSummaryVO>> stockSummary(@RequestParam(required = false) Long productId,
                                                                @RequestParam(required = false) LocalDate startDate,
                                                                @RequestParam(required = false) LocalDate endDate,
                                                                @RequestParam(required = false) Long warehouseId,
                                                                @RequestParam(defaultValue = "1") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.stockSummaryPage(productId, startDate, endDate, warehouseId, page, size));
    }

    @GetMapping("/near-expiry-list")
    @PreAuthorize("hasAuthority('report:view')")
    public ApiResponse<List<NearExpiryItemVO>> nearExpiryList(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(service.nearExpiryList(days));
    }

    @GetMapping("/inventory-balance")
    @PreAuthorize("hasAuthority('report:view')")
    public ApiResponse<PageResult<InventoryBalanceVO>> inventoryBalance(@RequestParam(required = false) Long productId,
                                                                        @RequestParam(required = false) Long warehouseId,
                                                                        @RequestParam(defaultValue = "1") int page,
                                                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.inventoryBalance(productId, warehouseId, page, size));
    }

    /** 毛利报表（P1-26）。 */
    @GetMapping("/gross-profit")
    @PreAuthorize("hasAuthority('report:view')")
    public ApiResponse<List<GrossProfitVO>> grossProfit(@RequestParam(required = false) Long productId,
                                                        @RequestParam(required = false) LocalDate startDate,
                                                        @RequestParam(required = false) LocalDate endDate) {
        return ApiResponse.ok(service.grossProfit(productId, startDate, endDate));
    }

    /** 近效期分页（P1-28）。 */
    @GetMapping("/near-expiry")
    @PreAuthorize("hasAuthority('report:view')")
    public ApiResponse<PageResult<NearExpiryItemVO>> nearExpiry(@RequestParam(defaultValue = "30") int days,
                                                                @RequestParam(defaultValue = "1") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.nearExpiryPage(days, page, size));
    }

    /** 近效期导出（EasyExcel，P1-28）。 */
    @GetMapping("/near-expiry/export")
    @PreAuthorize("hasAuthority('report:view')")
    public void exportNearExpiry(@RequestParam(defaultValue = "30") int days,
                                 HttpServletResponse response) throws IOException {
        List<NearExpiryItemVO> list = service.nearExpiryList(days);
        List<NearExpiryExportRow> rows = list.stream()
                .map(i -> new NearExpiryExportRow(i.productCode(), i.productName(), i.spec(), i.manufacturer(),
                        i.batchNo(),
                        i.produceDate() == null ? "" : i.produceDate().toString(),
                        i.expireDate().toString(), i.daysLeft(), i.quantity()))
                .toList();
        String filename = URLEncoder.encode("近效期清单_" + LocalDate.now() + ".xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + filename);
        EasyExcel.write(response.getOutputStream(), NearExpiryExportRow.class)
                .sheet("近效期清单")
                .doWrite(rows);
        // P1-2 导出留痕：谁在什么条件导出了多少行（含来源 IP）
        auditLogService.log(SecurityUtils.currentUser(), "EXPORT", "NEAR_EXPIRY", null,
                "导出近效期清单 days=" + days + "，共 " + rows.size() + " 行", null,
                AuditLogService.json("days", days, "rows", rows.size(),
                        "filename", "近效期清单_" + LocalDate.now() + ".xlsx"));
    }

    /** 工作台 Dashboard 卡片（P1-29）。 */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('report:view')")
    public ApiResponse<DashboardVO> dashboard() {
        return ApiResponse.ok(service.dashboard());
    }

    /** 进销存汇总导出（EasyExcel，P2-7）。 */
    @GetMapping("/stock-summary/export")
    @PreAuthorize("hasAuthority('report:view')")
    public void exportStockSummary(@RequestParam(required = false) Long productId,
                                   @RequestParam(required = false) LocalDate startDate,
                                   @RequestParam(required = false) LocalDate endDate,
                                   @RequestParam(required = false) Long warehouseId,
                                   HttpServletResponse response) throws IOException {
        List<StockSummaryVO> list = service.stockSummary(productId, startDate, endDate, warehouseId);
        List<StockSummaryExportRow> rows = list.stream()
                .map(i -> new StockSummaryExportRow(i.productCode(), i.genericName(), i.tradeName(), i.spec(),
                        i.manufacturer(), i.openingQty(), i.inboundQty(), i.outboundQty(), i.closingQty(),
                        i.openingAmount(), i.inboundAmount(), i.outboundAmount(), i.closingAmount()))
                .toList();
        String filename = "进销存汇总_" + LocalDate.now() + ".xlsx";
        writeExcel(response, "进销存汇总", filename, StockSummaryExportRow.class, rows);
        auditLogService.log(SecurityUtils.currentUser(), "EXPORT", "STOCK_SUMMARY", null,
                "导出进销存汇总，共 " + rows.size() + " 行", null,
                AuditLogService.json("productId", productId, "startDate", startDate, "endDate", endDate,
                        "warehouseId", warehouseId, "rows", rows.size(), "filename", filename));
    }

    /** 毛利报表导出（EasyExcel，P2-7）。 */
    @GetMapping("/gross-profit/export")
    @PreAuthorize("hasAuthority('report:view')")
    public void exportGrossProfit(@RequestParam(required = false) Long productId,
                                  @RequestParam(required = false) LocalDate startDate,
                                  @RequestParam(required = false) LocalDate endDate,
                                  HttpServletResponse response) throws IOException {
        List<GrossProfitVO> list = service.grossProfit(productId, startDate, endDate);
        List<GrossProfitExportRow> rows = list.stream()
                .map(i -> new GrossProfitExportRow(i.productCode(), i.productName(), i.quantity(), i.revenue(),
                        i.cost(), i.grossProfit(), i.grossMargin()))
                .toList();
        String filename = "毛利报表_" + LocalDate.now() + ".xlsx";
        writeExcel(response, "毛利报表", filename, GrossProfitExportRow.class, rows);
        auditLogService.log(SecurityUtils.currentUser(), "EXPORT", "GROSS_PROFIT", null,
                "导出毛利报表，共 " + rows.size() + " 行", null,
                AuditLogService.json("productId", productId, "startDate", startDate, "endDate", endDate,
                        "rows", rows.size(), "filename", filename));
    }

    private <T> void writeExcel(HttpServletResponse response, String sheetName, String filename,
                                Class<T> clazz, List<T> rows) throws IOException {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + encoded);
        EasyExcel.write(response.getOutputStream(), clazz).sheet(sheetName).doWrite(rows);
    }
}
