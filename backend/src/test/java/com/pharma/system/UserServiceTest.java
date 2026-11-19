package com.pharma.system;

import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.system.dto.AssignRolesRequest;
import com.pharma.system.dto.ChangePasswordRequest;
import com.pharma.system.dto.CreateUserRequest;
import com.pharma.system.dto.UpdateUserRequest;
import com.pharma.system.dto.UserVO;
import com.pharma.system.entity.SysUser;
import com.pharma.system.repository.SysUserRepository;
import com.pharma.system.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-6 用户管理测试：CRUD、改密、启停、角色分配、禁删自己、最后管理员保护。
 */
@SpringBootTest
@Transactional
class UserServiceTest {

    @Autowired
    private UserService userService;
    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("新建用户：bcrypt 加密、默认启用、分配角色、列表可检索")
    void createUserWithRoles() {
        String name = "u-" + System.nanoTime();
        UserVO vo = as("boss", () -> userService.create(
                new CreateUserRequest(name, "pwd12345", "测试用户", null, List.of("SALES"))));

        assertThat(vo.username()).isEqualTo(name);
        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.roles()).containsExactly("SALES");

        SysUser saved = userRepository.findByUsername(name).orElseThrow();
        assertThat(saved.getPasswordBcrypt()).isNotEqualTo("pwd12345");
        assertThat(passwordEncoder.matches("pwd12345", saved.getPasswordBcrypt())).isTrue();
        assertThat(saved.getTokenVersion()).isEqualTo(1);

        PageResult<UserVO> page = as("boss", () -> userService.list(name, 1, 10));
        assertThat(page.getRecords()).extracting(UserVO::username).containsExactly(name);
    }

    @Test
    @DisplayName("用户名重复 -> 400")
    void createDuplicateRejected() {
        String name = "dup-" + System.nanoTime();
        as("boss", () -> userService.create(new CreateUserRequest(name, "pwd12345", null, null, null)));
        assertThatThrownBy(() -> as("boss", () -> userService.create(
                new CreateUserRequest(name, "pwd12345", null, null, null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名已存在");
    }

    @Test
    @DisplayName("编辑资料 + 改密（旧 token 版本 +1）+ 启停切换")
    void updatePasswordToggle() {
        String name = "u2-" + System.nanoTime();
        UserVO vo = as("boss", () -> userService.create(
                new CreateUserRequest(name, "pwd12345", "原名", null, List.of("SALES"))));

        UserVO updated = as("boss", () -> userService.update(vo.id(), new UpdateUserRequest("新名", null, null)));
        assertThat(updated.realName()).isEqualTo("新名");

        asVoid("boss", () -> userService.changePassword(vo.id(), new ChangePasswordRequest("newpwd123")));
        SysUser saved = userRepository.findById(vo.id()).orElseThrow();
        assertThat(passwordEncoder.matches("newpwd123", saved.getPasswordBcrypt())).isTrue();
        assertThat(saved.getTokenVersion()).isEqualTo(2);

        UserVO disabled = as("boss", () -> userService.toggle(vo.id()));
        assertThat(disabled.status()).isEqualTo("INACTIVE");
        UserVO enabled = as("boss", () -> userService.toggle(vo.id()));
        assertThat(enabled.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("角色分配：全量覆盖；非法角色 -> 400")
    void assignRoles() {
        String name = "u3-" + System.nanoTime();
        UserVO vo = as("boss", () -> userService.create(
                new CreateUserRequest(name, "pwd12345", null, "ACTIVE", List.of("SALES"))));

        UserVO assigned = as("boss", () -> userService.assignRoles(vo.id(),
                new AssignRolesRequest(List.of("warehouse", "QUALITY"))));
        assertThat(assigned.roles()).containsExactlyInAnyOrder("WAREHOUSE", "QUALITY");

        assertThatThrownBy(() -> as("boss", () -> userService.assignRoles(vo.id(),
                new AssignRolesRequest(List.of("NOT_EXIST")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色不存在");
    }

    @Test
    @DisplayName("禁止删除当前登录账号")
    void forbidDeleteSelf() {
        String name = "self-" + System.nanoTime();
        UserVO vo = as("boss", () -> userService.create(
                new CreateUserRequest(name, "pwd12345", null, null, null)));

        assertThatThrownBy(() -> asVoid(name, () -> userService.delete(vo.id())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能删除当前登录账号");
    }

    @Test
    @DisplayName("不允许停用/降权/删除最后一个启用中的管理员")
    void guardLastAdmin() {
        // 先把库内所有管理员置为停用，构造「仅剩一个管理员」的场景（事务回滚，不影响真实数据）
        userRepository.findAll().stream()
                .filter(u -> userRepository.countByUserIdAndRoleCode(u.getId(), "ADMIN") > 0)
                .forEach(u -> {
                    u.setStatus("INACTIVE");
                    userRepository.save(u);
                });

        String name = "only-admin-" + System.nanoTime();
        UserVO lastAdmin = as("ops", () -> userService.create(
                new CreateUserRequest(name, "pwd12345", "唯一管理员", "ACTIVE", List.of("ADMIN"))));
        assertThat(userRepository.countActiveByRoleCode("ADMIN")).isEqualTo(1);

        assertThatThrownBy(() -> as("ops", () -> userService.toggle(lastAdmin.id())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最后一个启用中的管理员");

        assertThatThrownBy(() -> as("ops", () -> userService.assignRoles(lastAdmin.id(),
                new AssignRolesRequest(List.of("SALES")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ADMIN");

        assertThatThrownBy(() -> asVoid("ops", () -> userService.delete(lastAdmin.id())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最后一个启用中的管理员");
    }

    /** 以指定用户名作为当前登录身份执行。 */
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

    private void asVoid(String username, Runnable action) {
        as(username, () -> {
            action.run();
            return null;
        });
    }
}
