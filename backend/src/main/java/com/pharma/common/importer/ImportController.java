package com.pharma.common.importer;

import com.pharma.common.ApiResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;

/**
 * 基础资料 Excel 导入接口（§13.3）：5 类资源共用一套路由。
 * GET  /api/{resource}/import/template
 * POST /api/{resource}/import/preview
 * POST /api/{resource}/import?duplicateStrategy=SKIP|UPDATE|ERROR
 */
@RestController
@RequestMapping("/api/{resource:products|suppliers|customers|warehouses|stores}")
public class ImportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @GetMapping("/import/template")
    @PreAuthorize("@importPermission.check(#resource)")
    public ResponseEntity<byte[]> template(@PathVariable String resource) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        importService.writeTemplate(resource, out);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(resource + "-import-template.xlsx")
                .build());
        return new ResponseEntity<>(out.toByteArray(), headers, HttpStatus.OK);
    }

    @PostMapping("/import/preview")
    @PreAuthorize("@importPermission.check(#resource)")
    public ApiResponse<ImportPreviewResult> preview(@PathVariable String resource,
                                                    @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(importService.preview(resource, file));
    }

    @PostMapping("/import")
    @PreAuthorize("@importPermission.check(#resource)")
    public ApiResponse<ImportResult> execute(@PathVariable String resource,
                                             @RequestParam("file") MultipartFile file,
                                             @RequestParam(defaultValue = "SKIP") String duplicateStrategy) {
        return ApiResponse.ok(importService.execute(resource, file, duplicateStrategy));
    }
}
