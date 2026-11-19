package com.pharma.base.controller;

import com.pharma.base.entity.Warehouse;
import com.pharma.base.service.WarehouseService;
import com.pharma.common.ApiResponse;
import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.common.batch.BatchOperationRunner;
import com.pharma.common.batch.BatchRequest;
import com.pharma.common.batch.BatchResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final WarehouseService service;
    private final BatchOperationRunner batchRunner;

    public WarehouseController(WarehouseService service, BatchOperationRunner batchRunner) {
        this.service = service;
        this.batchRunner = batchRunner;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('warehouse:view')")
    public ApiResponse<PageResult<Warehouse>> list(@RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:view')")
    public ApiResponse<Warehouse> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('warehouse:create')")
    public ApiResponse<Warehouse> create(@Valid @RequestBody Warehouse warehouse) {
        return ApiResponse.ok(service.create(warehouse));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:update')")
    public ApiResponse<Warehouse> update(@PathVariable Long id, @Valid @RequestBody Warehouse warehouse) {
        return ApiResponse.ok(service.update(id, warehouse));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('warehouse:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    /** 批量操作（§13.2）：CREATE/UPDATE 用 items，DELETE/ENABLE/DISABLE 用 ids；每条独立事务。 */
    @PostMapping("/batch")
    @PreAuthorize("@batchPermission.check('warehouse', #request)")
    public ApiResponse<BatchResult> batch(@RequestBody BatchRequest<Warehouse> request) {
        return ApiResponse.ok(batchRunner.run("warehouse", request,
                w -> service.create(w).getId(),
                w -> {
                    if (w.getId() == null) {
                        throw BusinessException.badRequest("缺少 id");
                    }
                    return service.update(w.getId(), w).getId();
                },
                id -> service.update(id, statusOnly("ACTIVE")).getId(),
                id -> service.update(id, statusOnly("INACTIVE")).getId(),
                id -> {
                    service.delete(id);
                    return id;
                }));
    }

    private Warehouse statusOnly(String status) {
        Warehouse w = new Warehouse();
        w.setStatus(status);
        return w;
    }
}
