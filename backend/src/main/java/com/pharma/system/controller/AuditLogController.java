package com.pharma.system.controller;

import com.pharma.common.ApiResponse;
import com.pharma.common.PageResult;
import com.pharma.system.entity.AuditLog;
import com.pharma.system.service.AuditLogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/system")
public class AuditLogController {

    private final AuditLogService service;

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    @GetMapping("/audit-logs")
    @PreAuthorize("hasAuthority('system:audit-log:view')")
    public ApiResponse<PageResult<AuditLog>> list(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(page, size));
    }
}
