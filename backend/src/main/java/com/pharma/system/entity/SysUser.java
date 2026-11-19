package com.pharma.system.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "sys_user")
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_bcrypt", nullable = false, length = 100)
    private String passwordBcrypt;

    @Column(name = "real_name", length = 50)
    private String realName;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    /** JWT 版本号：禁用/改密/登出 +1，旧 token 因版本不匹配立即失效（P1-24）。 */
    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 1;

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

    public String getPasswordBcrypt() {
        return passwordBcrypt;
    }

    public void setPasswordBcrypt(String passwordBcrypt) {
        this.passwordBcrypt = passwordBcrypt;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(Integer tokenVersion) {
        this.tokenVersion = tokenVersion;
    }
}
