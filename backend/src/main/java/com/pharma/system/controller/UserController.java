package com.pharma.system.controller;

import com.pharma.common.ApiResponse;
import com.pharma.common.PageResult;
import com.pharma.system.dto.AssignRolesRequest;
import com.pharma.system.dto.ChangePasswordRequest;
import com.pharma.system.dto.CreateUserRequest;
import com.pharma.system.dto.UpdateUserRequest;
import com.pharma.system.dto.UserVO;
import com.pharma.system.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 用户管理（P0-6）：分页查询、新建、编辑、删除、改密、启停、角色分配。
 */
@RestController
@RequestMapping("/api/system/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:user:view')")
    public ApiResponse<PageResult<UserVO>> list(@RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(userService.list(keyword, page, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:user:create')")
    public ApiResponse<UserVO> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(userService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:update')")
    public ApiResponse<UserVO> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.ok(userService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('system:user:update')")
    public ApiResponse<Void> changePassword(@PathVariable Long id,
                                            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(id, request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAuthority('system:user:update')")
    public ApiResponse<UserVO> toggle(@PathVariable Long id) {
        return ApiResponse.ok(userService.toggle(id));
    }

    @PostMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:update')")
    public ApiResponse<UserVO> assignRoles(@PathVariable Long id,
                                           @Valid @RequestBody AssignRolesRequest request) {
        return ApiResponse.ok(userService.assignRoles(id, request));
    }
}
