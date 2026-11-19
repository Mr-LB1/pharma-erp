package com.pharma.system.dto;

import java.util.List;

/**
 * 当前登录用户信息（含角色与权限码列表，前端据此做菜单/按钮级显隐）。
 */
public class UserInfo {

    private Long id;
    private String username;
    private String realName;
    private List<String> roles;
    private List<String> permissions;

    public UserInfo() {
    }

    public UserInfo(Long id, String username, String realName, List<String> roles, List<String> permissions) {
        this.id = id;
        this.username = username;
        this.realName = realName;
        this.roles = roles;
        this.permissions = permissions;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }
}
