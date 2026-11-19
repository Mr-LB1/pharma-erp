package com.pharma.inventory.controller;

import com.pharma.common.ApiResponse;
import com.pharma.common.PageResult;
import com.pharma.inventory.dto.FefoPreviewVO;
import com.pharma.inventory.dto.StockVO;
import com.pharma.inventory.service.InventoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/stocks")
    @PreAuthorize("hasAuthority('inventory:stock:view')")
    public ApiResponse<PageResult<StockVO>> list(@RequestParam(required = false) Long productId,
                                                 @RequestParam(required = false) String batchNo,
                                                 @RequestParam(required = false) Long warehouseId,
                                                 @RequestParam(required = false) LocalDate expireBefore,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(productId, batchNo, warehouseId, expireBefore, page, size));
    }

    @GetMapping("/stocks/near-expiry")
    @PreAuthorize("hasAuthority('inventory:stock:view')")
    public ApiResponse<List<StockVO>> nearExpiry(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(service.nearExpiry(days));
    }

    /** #13 出库前 FEFO 批次预览：只读事务、零副作用、不返回成本。 */
    @GetMapping("/fefo-preview")
    @PreAuthorize("hasAuthority('inventory:stock:view')")
    @Transactional(readOnly = true)
    public ApiResponse<FefoPreviewVO> fefoPreview(@RequestParam Long productId,
                                                  @RequestParam Long warehouseId,
                                                  @RequestParam BigDecimal quantity) {
        return ApiResponse.ok(service.fefoPreview(productId, warehouseId, quantity));
    }
}
