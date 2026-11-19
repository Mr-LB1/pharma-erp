package com.pharma.quality.controller;

import com.pharma.approval.dto.ApprovalActionRequest;
import com.pharma.common.ApiResponse;
import com.pharma.common.PageResult;
import com.pharma.quality.dto.FirstAuditPendingVO;
import com.pharma.quality.dto.FirstAuditResult;
import com.pharma.quality.dto.LicenseWarning;
import com.pharma.quality.service.FirstAuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/quality")
public class FirstAuditController {

    private final FirstAuditService service;

    public FirstAuditController(FirstAuditService service) {
        this.service = service;
    }

    @PostMapping("/first-audits/{type}/{id}/approve")
    @PreAuthorize("hasAuthority('quality:first-audit:approve')")
    public ApiResponse<FirstAuditResult> approve(@PathVariable String type,
                                                 @PathVariable Long id,
                                                 @RequestBody(required = false) ApprovalActionRequest request) {
        return ApiResponse.ok(service.approve(type, id, request == null ? null : request.comment()));
    }

    @PostMapping("/first-audits/{type}/{id}/reject")
    @PreAuthorize("hasAuthority('quality:first-audit:approve')")
    public ApiResponse<FirstAuditResult> reject(@PathVariable String type,
                                                @PathVariable Long id,
                                                @RequestBody(required = false) ApprovalActionRequest request) {
        return ApiResponse.ok(service.reject(type, id, request == null ? null : request.comment()));
    }

    @GetMapping("/first-audits/pending")
    @PreAuthorize("hasAuthority('quality:first-audit:view')")
    public ApiResponse<PageResult<FirstAuditPendingVO>> pending(@RequestParam(required = false) String type,
                                                                @RequestParam(defaultValue = "1") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.pending(type, page, size));
    }

    @GetMapping("/expiring-licenses")
    @PreAuthorize("hasAuthority('quality:license:view')")
    public ApiResponse<List<LicenseWarning>> expiringLicenses(@RequestParam(defaultValue = "90") int days) {
        return ApiResponse.ok(service.expiringLicenses(days));
    }
}
