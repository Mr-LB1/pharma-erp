package com.pharma.approval.controller;

import com.pharma.approval.dto.ApprovalActionRequest;
import com.pharma.approval.dto.ApprovalPendingVO;
import com.pharma.approval.dto.ApprovalRecordVO;
import com.pharma.approval.service.ApprovalService;
import com.pharma.common.ApiResponse;
import com.pharma.common.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @PostMapping("/{bizType}/{bizId}/approve")
    @PreAuthorize("hasAuthority('approval:approve')")
    public ApiResponse<ApprovalRecordVO> approve(@PathVariable String bizType,
                                                 @PathVariable Long bizId,
                                                 @RequestBody(required = false) ApprovalActionRequest request) {
        return ApiResponse.ok(service.approve(bizType, bizId, request == null ? null : request.comment()));
    }

    @PostMapping("/{bizType}/{bizId}/reject")
    @PreAuthorize("hasAuthority('approval:approve')")
    public ApiResponse<ApprovalRecordVO> reject(@PathVariable String bizType,
                                                @PathVariable Long bizId,
                                                @RequestBody(required = false) ApprovalActionRequest request) {
        return ApiResponse.ok(service.reject(bizType, bizId, request == null ? null : request.comment()));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('approval:view')")
    public ApiResponse<PageResult<ApprovalPendingVO>> pending(@RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.pending(page, size));
    }
}
