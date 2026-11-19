package com.pharma.system.service;

import com.pharma.common.BusinessException;
import com.pharma.common.SecurityUtils;
import com.pharma.system.entity.SysPermission;
import com.pharma.system.entity.SysRole;
import com.pharma.system.repository.RolePermissionRepository;
import com.pharma.system.repository.SysPermissionRepository;
import com.pharma.system.repository.SysRoleRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 角色服务：角色列表 + 角色权限配置（全量覆盖）。
 * 约束：权限码必须存在于 sys_permission；ADMIN 必须保留 system:role:config，避免管理员自锁。
 */
@Service
public class RoleService {

    private static final String ADMIN = "ADMIN";
    private static final String ROLE_CONFIG = "system:role:config";

    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final AuditLogService auditLogService;

    public RoleService(SysRoleRepository roleRepository,
                       SysPermissionRepository permissionRepository,
                       RolePermissionRepository rolePermissionRepository,
                       AuditLogService auditLogService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.auditLogService = auditLogService;
    }

    public List<SysRole> list() {
        return roleRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    /** 全部权限码（按 code 排序），供前端权限勾选面板使用。 */
    public List<SysPermission> listAllPermissions() {
        return permissionRepository.findAll(Sort.by(Sort.Direction.ASC, "code"));
    }

    public SysRole getRole(Long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> BusinessException.notFound("角色不存在: " + roleId));
    }

    /** 角色当前权限码（按 code 排序）。 */
    public List<String> getPermissions(Long roleId) {
        getRole(roleId);
        return rolePermissionRepository.findPermissionCodesByRoleId(roleId);
    }

    /**
     * 全量覆盖角色权限：校验 → 删旧映射 → 插新映射 → 审计。返回更新后的权限码列表。
     */
    @Transactional
    public List<String> updatePermissions(Long roleId, List<String> codes) {
        SysRole role = getRole(roleId);
        Set<String> target = normalizeCodes(codes);

        Map<String, Long> known = permissionIdByCode();
        List<String> unknown = target.stream().filter(c -> !known.containsKey(c)).toList();
        if (!unknown.isEmpty()) {
            throw BusinessException.badRequest("未知权限码: " + String.join(", ", unknown));
        }
        if (ADMIN.equals(role.getCode()) && !target.contains(ROLE_CONFIG)) {
            throw BusinessException.badRequest("不能移除 ADMIN 的角色配置权限");
        }

        List<String> before = rolePermissionRepository.findPermissionCodesByRoleId(roleId);
        rolePermissionRepository.deleteByRoleId(roleId);
        for (String code : target) {
            rolePermissionRepository.insert(roleId, known.get(code));
        }

        Set<String> added = new TreeSet<>(target);
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(target);
        auditLogService.log(SecurityUtils.currentUser(), "ROLE_PERMISSION_UPDATE", "SYS_ROLE", roleId,
                "角色 " + role.getCode() + " 权限更新，新增 " + added + "，移除 " + removed,
                AuditLogService.json("code", role.getCode(), "permissions", new TreeSet<>(before)),
                AuditLogService.json("code", role.getCode(), "permissions", new TreeSet<>(target),
                        "added", added, "removed", removed));

        return rolePermissionRepository.findPermissionCodesByRoleId(roleId);
    }

    private Map<String, Long> permissionIdByCode() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (SysPermission p : permissionRepository.findAll(Sort.by(Sort.Direction.ASC, "code"))) {
            map.put(p.getCode(), p.getId());
        }
        return map;
    }

    /** 去空白、去重；权限码区分大小写（形如 product:view），不做大小写归一。 */
    private Set<String> normalizeCodes(List<String> codes) {
        Set<String> result = new LinkedHashSet<>();
        if (codes != null) {
            for (String code : codes) {
                if (code != null && !code.isBlank()) {
                    result.add(code.trim());
                }
            }
        }
        return result;
    }
}
