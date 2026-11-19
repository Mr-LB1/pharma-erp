package com.pharma.config;

import com.pharma.system.repository.SysPermissionRepository;
import com.pharma.system.repository.SysRoleRepository;
import com.pharma.system.entity.SysUser;
import com.pharma.system.repository.SysUserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 从数据库加载用户 + 角色 + 权限码，供 Spring Security 与 JWT 过滤器使用。
 * authorities = ROLE_<code>（角色） + 权限码（如 product:create，供 hasAuthority 使用）。
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;

    public CustomUserDetailsService(SysUserRepository userRepository,
                                    SysRoleRepository roleRepository,
                                    SysPermissionRepository permissionRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));

        List<GrantedAuthority> authorities = new ArrayList<>();
        roleRepository.findRoleCodesByUserId(user.getId())
                .forEach(code -> authorities.add(new SimpleGrantedAuthority("ROLE_" + code)));
        permissionRepository.findPermissionCodesByUserId(user.getId())
                .forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));

        boolean enabled = "ACTIVE".equals(user.getStatus());
        return new User(user.getUsername(), user.getPasswordBcrypt(),
                enabled, true, true, true, authorities);
    }
}
