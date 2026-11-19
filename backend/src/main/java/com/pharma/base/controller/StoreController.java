package com.pharma.base.controller;

import com.pharma.base.dto.StoreLicenseWarning;
import com.pharma.base.entity.Store;
import com.pharma.base.service.StoreService;
import com.pharma.common.ApiResponse;
import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.common.batch.BatchOperationRunner;
import com.pharma.common.batch.BatchRequest;
import com.pharma.common.batch.BatchResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
public class StoreController {

    private final StoreService service;
    private final BatchOperationRunner batchRunner;

    public StoreController(StoreService service, BatchOperationRunner batchRunner) {
        this.service = service;
        this.batchRunner = batchRunner;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('base:store:view')")
    public ApiResponse<PageResult<Store>> list(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    /** 证照到期预警：days 天内到期（含已过期）。 */
    @GetMapping("/expiring-licenses")
    @PreAuthorize("hasAuthority('base:store:view')")
    public ApiResponse<List<StoreLicenseWarning>> expiringLicenses(@RequestParam(defaultValue = "90") int days) {
        return ApiResponse.ok(service.expiringLicenses(days));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('base:store:view')")
    public ApiResponse<Store> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('base:store:create')")
    public ApiResponse<Store> create(@Valid @RequestBody Store store) {
        return ApiResponse.ok(service.create(store));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('base:store:update')")
    public ApiResponse<Store> update(@PathVariable Long id, @RequestBody Store store) {
        return ApiResponse.ok(service.update(id, store));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('base:store:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    /**
     * 批量操作（§13.2）：CREATE/UPDATE 用 items，DELETE/ENABLE/DISABLE 用 ids；每条独立事务。
     * 门店模块沿用的权限命名空间为 base:store:*（见 V10），故前缀传 base:store。
     */
    @PostMapping("/batch")
    @PreAuthorize("@batchPermission.check('base:store', #request)")
    public ApiResponse<BatchResult> batch(@RequestBody BatchRequest<Store> request) {
        return ApiResponse.ok(batchRunner.run("store", request,
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

    private Store statusOnly(String status) {
        Store s = new Store();
        s.setStatus(status);
        return s;
    }
}
