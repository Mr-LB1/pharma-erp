package com.pharma.common.export;

import com.alibaba.excel.EasyExcel;
import com.pharma.common.SecurityUtils;
import com.pharma.system.service.AuditLogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 单据导出统一写出器（对齐 ReportController 的 EasyExcel 范式）：
 * 设置 xlsx Content-Type / 文件名 → 写入工作簿 → 记录 EXPORT 审计。
 * 超过上限被截断时，响应头 X-Export-Truncated=true 并在审计 detail 中注明命中总数。
 */
@Component
public class ExportWriter {

    /** 单次导出上限（全量查询，但不允许无限拉取）。 */
    public static final int MAX_ROWS = 10_000;

    public static final String HEADER_ROWS = "X-Export-Rows";
    public static final String HEADER_TRUNCATED = "X-Export-Truncated";

    private final AuditLogService auditLogService;

    public ExportWriter(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * @param sheetName  工作表名
     * @param baseName   文件名主体（自动追加日期与 .xlsx）
     * @param objectType 审计对象类型，如 PURCHASE_ORDER
     * @param condition  查询条件描述，如 "keyword=张三, status=APPROVED"
     */
    public <T> void write(HttpServletResponse response, String sheetName, String baseName, String objectType,
                          String condition, Class<T> clazz, ExportPayload<T> payload) throws IOException {
        List<T> rows = payload.rows();
        String filename = baseName + "_" + LocalDate.now() + ".xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + encoded);
        response.setHeader(HEADER_ROWS, String.valueOf(rows.size()));
        response.setHeader(HEADER_TRUNCATED, String.valueOf(payload.truncated()));
        EasyExcel.write(response.getOutputStream(), clazz).sheet(sheetName).doWrite(rows);

        // 导出留痕：谁在什么条件下导出了多少行（含截断提示）
        auditLogService.log(SecurityUtils.currentUser(), "EXPORT", objectType, null,
                "导出" + baseName + "（" + condition + "），共 " + rows.size() + " 行"
                        + (payload.truncated() ? "；命中 " + payload.total() + " 行，已按上限 " + payload.limit() + " 行截断" : ""),
                null, AuditLogService.json("condition", condition, "rows", rows.size(),
                        "total", payload.total(), "limit", payload.limit(),
                        "truncated", payload.truncated(), "filename", filename));
    }
}
