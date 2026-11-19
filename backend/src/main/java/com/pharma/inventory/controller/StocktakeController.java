package com.pharma.inventory.controller;

import com.pharma.common.ApiResponse;
import com.pharma.common.PageResult;
import com.pharma.inventory.dto.StocktakeCreateRequest;
import com.pharma.inventory.dto.StocktakeVO;
import com.pharma.inventory.service.StocktakeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory/stocktakes")
public class StocktakeController {

    private final StocktakeService service;

    public StocktakeController(StocktakeService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('inventory:stocktake:create')")
    public ApiResponse<StocktakeVO> create(@RequestBody StocktakeCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('inventory:stocktake:submit')")
    public ApiResponse<StocktakeVO> submit(@PathVariable Long id) {
        return ApiResponse.ok(service.submit(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory:stocktake:view')")
    public ApiResponse<PageResult<StocktakeVO>> list(@RequestParam(required = false) String keyword,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, page, size));
    }
}
