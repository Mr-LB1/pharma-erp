package com.pharma.trace.controller;

import com.pharma.common.ApiResponse;
import com.pharma.trace.dto.TraceResult;
import com.pharma.trace.service.TraceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private final TraceService service;

    public TraceController(TraceService service) {
        this.service = service;
    }

    @GetMapping("/forward")
    @PreAuthorize("hasAuthority('trace:view')")
    public ApiResponse<TraceResult> forward(@RequestParam Long productId,
                                            @RequestParam String batchNo) {
        return ApiResponse.ok(service.forward(productId, batchNo));
    }

    @GetMapping("/backward")
    @PreAuthorize("hasAuthority('trace:view')")
    public ApiResponse<TraceResult> backward(@RequestParam Long productId,
                                             @RequestParam String batchNo) {
        return ApiResponse.ok(service.backward(productId, batchNo));
    }
}
