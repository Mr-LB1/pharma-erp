package com.pharma.base.controller;

import com.pharma.base.entity.Product;
import com.pharma.base.service.ProductService;
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
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;
    private final BatchOperationRunner batchRunner;

    public ProductController(ProductService service, BatchOperationRunner batchRunner) {
        this.service = service;
        this.batchRunner = batchRunner;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('product:view')")
    public ApiResponse<PageResult<Product>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, status, page, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:create')")
    public ApiResponse<Product> create(@Valid @RequestBody Product product) {
        return ApiResponse.ok(service.create(product));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('product:view')")
    public ApiResponse<Product> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('product:update')")
    public ApiResponse<Product> update(@PathVariable Long id, @Valid @RequestBody Product product) {
        return ApiResponse.ok(service.update(id, product));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('product:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    /** 批量操作（§13.2）：CREATE/UPDATE 用 items，DELETE/ENABLE/DISABLE 用 ids；每条独立事务。 */
    @PostMapping("/batch")
    @PreAuthorize("@batchPermission.check('product', #request)")
    public ApiResponse<BatchResult> batch(@RequestBody BatchRequest<Product> request) {
        return ApiResponse.ok(batchRunner.run("product", request,
                p -> service.create(p).getId(),
                p -> {
                    if (p.getId() == null) {
                        throw BusinessException.badRequest("缺少 id");
                    }
                    return service.update(p.getId(), p).getId();
                },
                id -> service.update(id, statusOnly("ACTIVE")).getId(),
                id -> service.update(id, statusOnly("INACTIVE")).getId(),
                id -> {
                    service.delete(id);
                    return id;
                }));
    }

    private Product statusOnly(String status) {
        Product p = new Product();
        p.setStatus(status);
        return p;
    }
}
