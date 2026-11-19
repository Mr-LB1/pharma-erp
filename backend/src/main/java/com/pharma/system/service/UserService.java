package com.pharma.system.service;

import com.pharma.common.BusinessException;
import com.pharma.common.PageResult;
import com.pharma.common.SecurityUtils;
import com.pharma.system.dto.AssignRolesRequest;
import com.pharma.system.dto.ChangePasswordRequest;
import com.pharma.system.dto.CreateUserRequest;
import com.pharma.system.dto.UpdateUserRequest;
import com.pharma.system.dto.UserVO;
import com.pharma.system.entity.SysRole;
import com.pharma.system.entity.SysUser;
import com.pharma.system.repository.SysRoleRepository;
import com.pharma.system.repository.SysUserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户管理（P0-6）：CRUD、改密、启停、角色分配。
 * 安全约束：禁止删除自己；禁止停用/降权最后一个启用中的管理员。
 * 改密与禁用都会使 tokenVersion +1，使旧 token 立即失效（P1-24）。
 */
@Service
public class UserService {

    private static final String ADMIN = "ADMIN";
    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;
    private final AuditLogService auditLogService;

    public UserService(SysUserRepository userRepository,
                       SysRoleRepository roleRepository,
                       PasswordEncoder passwordEncoder,
                       JdbcTemplate jdbcTemplate,
                       AuthService authService,
                       AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
        this.auditLogService = auditLogService;
    }

    public PageResult<UserVO> list(String keyword, int page, int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        Pageable pageable = PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "id"));
        Page<SysUser> result = userRepository.search(keyword, pageable);
        List<UserVO> records = result.getContent().stream().map(this::toVO).toList();
        return new PageResult<>(records, result.getTotalElements(), p, s);
    }

    @Transactional
    public UserVO create(CreateUserRequest req) {
        String username = req.username() == null ? "" : req.username().trim();
        if (username.isEmpty()) {
            throw BusinessException.badRequest("用户名不能为空");
        }
        if (userRepository.existsByUsername(username)) {
            throw BusinessException.badRequest("用户名已存在: " + username);
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordBcrypt(passwordEncoder.encode(req.password()));
        user.setRealName(req.realName());
        user.setStatus(normalizeStatus(req.status(), ACTIVE));
        user = userRepository.save(user);

        if (req.roleCodes() != null && !req.roleCodes().isEmpty()) {
            replaceRoles(user.getId(), req.roleCodes());
        }
        auditLogService.log(SecurityUtils.currentUser(), "CREATE_USER", "SYS_USER", user.getId(),
                "创建用户 " + username + "，角色 " + roleRepository.findRoleCodesByUserId(user.getId()));
        return toVO(user);
    }

    @Transactional
    public UserVO update(Long id, UpdateUserRequest req) {
        SysUser user = require(id);
        if (req.realName() != null) {
            user.setRealName(req.realName());
        }
        if (req.status() != null && !req.status().isBlank()) {
            String status = normalizeStatus(req.status(), user.getStatus());
            if (INACTIVE.equals(status) && !INACTIVE.equals(user.getStatus())) {
                if (user.getUsername().equals(SecurityUtils.currentUser())) {
                    throw BusinessException.badRequest("不能停用当前登录账号");
                }
                guardLastAdmin(user, "停用");
                authService.revokeTokens(user);
            }
            user.setStatus(status);
        }
        if (req.roleCodes() != null) {
            Set<String> codes = normalizeCodes(req.roleCodes());
            if (!codes.contains(ADMIN)
                    && userRepository.countByUserIdAndRoleCode(id, ADMIN) > 0
                    && ACTIVE.equals(user.getStatus())
                    && userRepository.countActiveByRoleCode(ADMIN) <= 1) {
                throw BusinessException.badRequest("不能移除最后一个启用中管理员的 ADMIN 角色");
            }
            replaceRoles(id, codes);
        }
        userRepository.save(user);
        auditLogService.log(SecurityUtils.currentUser(), "UPDATE_USER", "SYS_USER", user.getId(),
                "编辑用户 " + user.getUsername());
        return toVO(user);
    }

    @Transactional
    public void delete(Long id) {
        SysUser user = require(id);
        if (user.getUsername().equals(SecurityUtils.currentUser())) {
            throw BusinessException.badRequest("不能删除当前登录账号");
        }
        if (userRepository.countByUserIdAndRoleCode(id, ADMIN) > 0
                && ACTIVE.equals(user.getStatus())
                && userRepository.countActiveByRoleCode(ADMIN) <= 1) {
            throw BusinessException.badRequest("不能删除最后一个启用中的管理员");
        }
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", id);
        userRepository.delete(user);
        auditLogService.log(SecurityUtils.currentUser(), "DELETE_USER", "SYS_USER", id,
                "删除用户 " + user.getUsername());
    }

    /** 管理员重置密码：bcrypt 加密入库，并吊销该用户所有旧 token。 */
    @Transactional
    public void changePassword(Long id, ChangePasswordRequest req) {
        SysUser user = require(id);
        user.setPasswordBcrypt(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);
        authService.revokeTokens(user);
        auditLogService.log(SecurityUtils.currentUser(), "CHANGE_PASSWORD", "SYS_USER", id,
                "重置用户 " + user.getUsername() + " 的密码");
    }

    /** 启停切换：ACTIVE <-> INACTIVE；停用/启用都会刷新 token 版本。 */
    @Transactional
    public UserVO toggle(Long id) {
        SysUser user = require(id);
        String next = ACTIVE.equals(user.getStatus()) ? INACTIVE : ACTIVE;
        if (INACTIVE.equals(next)) {
            if (user.getUsername().equals(SecurityUtils.currentUser())) {
                throw BusinessException.badRequest("不能停用当前登录账号");
            }
            guardLastAdmin(user, "停用");
        }
        user.setStatus(next);
        userRepository.save(user);
        authService.revokeTokens(user);
        auditLogService.log(SecurityUtils.currentUser(), "TOGGLE_USER", "SYS_USER", id,
                "用户 " + user.getUsername() + " 状态改为 " + next);
        return toVO(user);
    }

    /** 角色分配：全量覆盖；禁止移除最后一个启用中管理员的 ADMIN 角色。 */
    @Transactional
    public UserVO assignRoles(Long id, AssignRolesRequest req) {
        SysUser user = require(id);
        Set<String> codes = normalizeCodes(req.roleCodes());
        boolean keepsAdmin = codes.contains(ADMIN);
        if (!keepsAdmin
                && userRepository.countByUserIdAndRoleCode(id, ADMIN) > 0
                && ACTIVE.equals(user.getStatus())
                && userRepository.countActiveByRoleCode(ADMIN) <= 1) {
            throw BusinessException.badRequest("不能移除最后一个启用中管理员的 ADMIN 角色");
        }
        replaceRoles(id, codes);
        auditLogService.log(SecurityUtils.currentUser(), "ASSIGN_ROLES", "SYS_USER", id,
                "用户 " + user.getUsername() + " 角色改为 " + codes);
        return toVO(user);
    }

    private void replaceRoles(Long userId, java.util.Collection<String> roleCodes) {
        Set<String> codes = normalizeCodes(roleCodes);
        List<SysRole> roles = codes.isEmpty() ? List.of() : roleRepository.findByCodeIn(codes);
        if (roles.size() != codes.size()) {
            Set<String> missing = new LinkedHashSet<>(codes);
            roles.forEach(r -> missing.remove(r.getCode()));
            throw BusinessException.badRequest("角色不存在: " + missing);
        }
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        for (SysRole role : roles) {
            jdbcTemplate.update("INSERT INTO sys_user_role(user_id, role_id) VALUES (?, ?)",
                    userId, role.getId());
        }
    }

    private Set<String> normalizeCodes(java.util.Collection<String> roleCodes) {
        Set<String> codes = new LinkedHashSet<>();
        if (roleCodes != null) {
            for (String code : roleCodes) {
                if (code != null && !code.isBlank()) {
                    codes.add(code.trim().toUpperCase());
                }
            }
        }
        return codes;
    }

    private String normalizeStatus(String status, String fallback) {
        if (status == null || status.isBlank()) {
            return fallback;
        }
        String s = status.trim().toUpperCase();
        if ("DISABLED".equals(s)) {
            s = INACTIVE;
        } else if ("ENABLED".equals(s)) {
            s = ACTIVE;
        }
        if (!ACTIVE.equals(s) && !INACTIVE.equals(s)) {
            throw BusinessException.badRequest("用户状态仅支持 ACTIVE / INACTIVE");
        }
        return s;
    }

    private void guardLastAdmin(SysUser user, String action) {
        if (userRepository.countByUserIdAndRoleCode(user.getId(), ADMIN) > 0
                && userRepository.countActiveByRoleCode(ADMIN) <= 1) {
            throw BusinessException.badRequest("不能" + action + "最后一个启用中的管理员");
        }
    }

    private SysUser require(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("用户不存在: " + id));
    }

    private UserVO toVO(SysUser user) {
        List<String> roles = new ArrayList<>(roleRepository.findRoleCodesByUserId(user.getId()));
        return new UserVO(user.getId(), user.getUsername(), user.getRealName(), user.getStatus(), roles);
    }
}
