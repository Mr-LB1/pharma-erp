package com.pharma.base.controller;

import com.pharma.base.entity.Supplier;
import com.pharma.base.service.SupplierService;
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
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierService service;
    private final BatchOperationRunner batchRunner;

    public SupplierController(SupplierService service, BatchOperationRunner batchRunner) {
        this.service = service;
        this.batchRunner = batchRunner;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('supplier:view')")
    public ApiResponse<PageResult<Supplier>> list(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('supplier:create')")
    public ApiResponse<Supplier> create(@Valid @RequestBody Supplier supplier) {
        return ApiResponse.ok(service.create(supplier));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('supplier:view')")
    public ApiResponse<Supplier> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('supplier:update')")
    public ApiResponse<Supplier> update(@PathVariable Long id, @Valid @RequestBody Supplier supplier) {
        return ApiResponse.ok(service.update(id, supplier));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('supplier:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    /** 批量操作（§13.2）：CREATE/UPDATE 用 items，DELETE/ENABLE/DISABLE 用 ids；每条独立事务。 */
    @PostMapping("/batch")
    @PreAuthorize("@batchPermission.check('supplier', #request)")
    public ApiResponse<BatchResult> batch(@RequestBody BatchRequest<Supplier> request) {
        return ApiResponse.ok(batchRunner.run("supplier", request,
                s -> service.create(s).getId(),
                s -> {
                    if (s.getId() == null) {
                        throw BusinessException.badRequest("缺少 id");
                    }
                    return service.update(s.getId(), s).getId();
                },
                id -> service.update(id, statusOnly("ACTIVE")).getId(),
                id -> service.update(id, statusOnly("INACTIVE")).getId(),
                id -> {
                    service.delete(id);
                    return id;
                }));
    }

    private Supplier statusOnly(String status) {
        Supplier s = new Supplier();
        s.setStatus(status);
        return s;
    }
}
