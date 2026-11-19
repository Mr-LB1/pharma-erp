package com.pharma.system;

import com.pharma.common.BusinessException;
import com.pharma.system.dto.CreateUserRequest;
import com.pharma.system.dto.UserVO;
import com.pharma.system.entity.AuditLog;
import com.pharma.system.repository.AuditLogRepository;
import com.pharma.system.repository.SysPermissionRepository;
import com.pharma.system.repository.SysRoleRepository;
import com.pharma.system.repository.SysUserRepository;
import com.pharma.system.service.RoleService;
import com.pharma.system.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 角色权限配置测试：读取默认权限、全量覆盖生效（含用户维度 /me 数据源）、非法权限码、
 * ADMIN 自锁保护、审计留痕。事务回滚，不改动真实数据。
 */
@SpringBootTest
@Transactional
class RolePermissionTest {

    @Autowired
    private RoleService roleService;
    @Autowired
    private SysRoleRepository roleRepository;
    @Autowired
    private SysPermissionRepository permissionRepository;
    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("读取 QUALITY 角色默认权限：非空且都来自权限字典")
    void getDefaultPermissions() {
        List<String> codes = roleService.getPermissions(roleId("QUALITY"));
        assertThat(codes).isNotEmpty();
        assertThat(codes).isSorted();
        assertThat(codes).isSubsetOf(allPermissionCodes());
    }

    @Test
    @DisplayName("更新角色权限：增删都生效，且拥有该角色的用户权限码同步变化")
    void updatePermissionsTakesEffect() {
        Long roleId = roleId("QUALITY");
        List<String> before = new ArrayList<>(roleService.getPermissions(roleId));

        List<String> target = new ArrayList<>(before);
        target.remove("warehouse:view");
        target.add("product:delete");

        List<String> after = as("admin", () -> roleService.updatePermissions(roleId, target));
        assertThat(after).contains("product:delete").doesNotContain("warehouse:view");
        assertThat(roleService.getPermissions(roleId)).isEqualTo(after);

        // 该角色的用户（/me 的权限数据源）实时反映变化
        UserVO user = as("admin", () -> userService.create(new CreateUserRequest(
                "q-" + System.nanoTime(), "pwd12345", "质量用户", null, List.of("QUALITY"))));
        List<String> userCodes = permissionRepository.findPermissionCodesByUserId(user.id());
        assertThat(userCodes).contains("product:delete").doesNotContain("warehouse:view");
    }

    @Test
    @DisplayName("未知权限码 -> 400")
    void unknownPermissionRejected() {
        Long roleId = roleId("QUALITY");
        assertThatThrownBy(() -> as("admin", () -> roleService.updatePermissions(roleId,
                List.of("product:view", "no:such:perm"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未知权限码: no:such:perm");

        // 校验失败不落库
        assertThat(roleService.getPermissions(roleId)).doesNotContain("no:such:perm");
    }

    @Test
    @DisplayName("ADMIN 不能移除 system:role:config（自锁保护），其余权限可配置")
    void adminCannotLoseConfigPermission() {
        Long adminId = roleId("ADMIN");
        List<String> current = roleService.getPermissions(adminId);
        assertThat(current).contains("system:role:config");

        List<String> withoutConfig = current.stream().filter(c -> !"system:role:config".equals(c)).toList();
        assertThatThrownBy(() -> as("admin", () -> roleService.updatePermissions(adminId, withoutConfig)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能移除 ADMIN 的角色配置权限");

        // 保留 config 时，允许移除其它权限
        List<String> kept = current.stream().filter(c -> !"product:view".equals(c)).toList();
        List<String> after = as("admin", () -> roleService.updatePermissions(adminId, kept));
        assertThat(after).contains("system:role:config").doesNotContain("product:view");
    }

    @Test
    @DisplayName("配置权限写审计日志：动作 + 角色 code + 增删 diff")
    void updateWritesAuditLog() {
        Long roleId = roleId("QUALITY");
        List<String> current = roleService.getPermissions(roleId);
        String fresh = allPermissionCodes().stream().filter(c -> !current.contains(c)).findFirst().orElseThrow();
        List<String> target = new ArrayList<>(current);
        target.add(fresh);

        as("admin", () -> roleService.updatePermissions(roleId, target));

        AuditLog log = auditLogRepository.findAll().stream()
                .filter(l -> "ROLE_PERMISSION_UPDATE".equals(l.getAction()) && roleId.equals(l.getObjectId()))
                .max(java.util.Comparator.comparing(AuditLog::getId))
                .orElseThrow();
        assertThat(log.getUsername()).isEqualTo("admin");
        assertThat(log.getObjectType()).isEqualTo("SYS_ROLE");
        assertThat(log.getDetail()).contains("QUALITY").contains(fresh);
    }

    @Test
    @DisplayName("角色不存在 -> 404；权限字典按 code 升序返回")
    void roleNotFoundAndPermissionDictionary() {
        assertThatThrownBy(() -> roleService.getPermissions(999_999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色不存在");

        List<String> codes = roleService.listAllPermissions().stream()
                .map(p -> p.getCode()).toList();
        assertThat(codes).isSorted().contains("system:role:config");
    }

    private Long roleId(String code) {
        return roleRepository.findByCode(code).orElseThrow().getId();
    }

    private List<String> allPermissionCodes() {
        return roleService.listAllPermissions().stream().map(p -> p.getCode()).toList();
    }

    private <T> T as(String username, java.util.function.Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
