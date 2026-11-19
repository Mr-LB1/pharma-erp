package com.pharma.config;

import com.pharma.system.entity.SysRole;
import com.pharma.system.entity.SysUser;
import com.pharma.system.repository.SysRoleRepository;
import com.pharma.system.repository.SysUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 初始化演示账号：admin / admin123（角色 ADMIN）。
 * 幂等：角色复用 V3 已初始化的 ADMIN；用户与用户-角色映射仅当不存在时插入。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(SysUserRepository userRepository,
                           SysRoleRepository roleRepository,
                           JdbcTemplate jdbcTemplate,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.existsByUsername("admin")) {
            return;
        }

        // V3 已初始化默认角色，这里复用 ADMIN；若缺失则兜底创建
        SysRole role = roleRepository.findByCode("ADMIN").orElseGet(() -> {
            SysRole r = new SysRole();
            r.setCode("ADMIN");
            r.setName("系统管理员");
            return roleRepository.save(r);
        });

        SysUser user = new SysUser();
        user.setUsername("admin");
        user.setPasswordBcrypt(passwordEncoder.encode("admin123"));
        user.setRealName("系统管理员");
        user.setStatus("ACTIVE");
        userRepository.save(user);

        jdbcTemplate.update("INSERT INTO sys_user_role(user_id, role_id) VALUES (?, ?)",
                user.getId(), role.getId());
    }
}
