package com.pharma.quality.controller;

import com.pharma.common.ApiResponse;
import com.pharma.common.PageResult;
import com.pharma.quality.dto.UnqualifiedCreateRequest;
import com.pharma.quality.dto.UnqualifiedDisposeRequest;
import com.pharma.quality.dto.UnqualifiedVO;
import com.pharma.quality.service.UnqualifiedService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quality/unqualified")
public class UnqualifiedController {

    private final UnqualifiedService service;

    public UnqualifiedController(UnqualifiedService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('quality:unqualified:create')")
    public ApiResponse<UnqualifiedVO> register(@RequestBody UnqualifiedCreateRequest request) {
        return ApiResponse.ok(service.register(request));
    }

    @PostMapping("/{id}/dispose")
    @PreAuthorize("hasAuthority('quality:unqualified:dispose')")
    public ApiResponse<UnqualifiedVO> dispose(@PathVariable Long id,
                                              @RequestBody(required = false) UnqualifiedDisposeRequest request) {
        return ApiResponse.ok(service.dispose(id, request));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('quality:unqualified:view')")
    public ApiResponse<PageResult<UnqualifiedVO>> list(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(page, size));
    }
}
