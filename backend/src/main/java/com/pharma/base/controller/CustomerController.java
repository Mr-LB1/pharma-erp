package com.pharma.base.controller;

import com.pharma.base.entity.Customer;
import com.pharma.base.service.CustomerService;
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
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService service;
    private final BatchOperationRunner batchRunner;

    public CustomerController(CustomerService service, BatchOperationRunner batchRunner) {
        this.service = service;
        this.batchRunner = batchRunner;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:view')")
    public ApiResponse<PageResult<Customer>> list(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('customer:create')")
    public ApiResponse<Customer> create(@Valid @RequestBody Customer customer) {
        return ApiResponse.ok(service.create(customer));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:view')")
    public ApiResponse<Customer> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:update')")
    public ApiResponse<Customer> update(@PathVariable Long id, @Valid @RequestBody Customer customer) {
        return ApiResponse.ok(service.update(id, customer));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    /** 批量操作（§13.2）：CREATE/UPDATE 用 items，DELETE/ENABLE/DISABLE 用 ids；每条独立事务。 */
    @PostMapping("/batch")
    @PreAuthorize("@batchPermission.check('customer', #request)")
    public ApiResponse<BatchResult> batch(@RequestBody BatchRequest<Customer> request) {
        return ApiResponse.ok(batchRunner.run("customer", request,
                c -> service.create(c).getId(),
                c -> {
                    if (c.getId() == null) {
                        throw BusinessException.badRequest("缺少 id");
                    }
                    return service.update(c.getId(), c).getId();
                },
                id -> service.update(id, statusOnly("ACTIVE")).getId(),
                id -> service.update(id, statusOnly("INACTIVE")).getId(),
                id -> {
                    service.delete(id);
                    return id;
                }));
    }

    private Customer statusOnly(String status) {
        Customer c = new Customer();
        c.setStatus(status);
        return c;
    }
}
