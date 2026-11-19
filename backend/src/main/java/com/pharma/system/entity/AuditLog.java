package com.pharma.system.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 审计日志（append-only）。
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50)
    private String username;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "object_type", length = 50)
    private String objectType;

    @Column(name = "object_id")
    private Long objectId;

    @Column(length = 1000)
    private String detail;

    /** 来源 IP（Nginx 场景取 X-Forwarded-For 首段；非 Web 线程为空）。 */
    @Column(length = 64)
    private String ip;

    /** 关键操作前值快照（JSON 文本，可空）。 */
    @Column(name = "before_value", columnDefinition = "text")
    private String beforeValue;

    /** 关键操作后值快照（JSON 文本，可空）。 */
    @Column(name = "after_value", columnDefinition = "text")
    private String afterValue;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public Long getObjectId() {
        return objectId;
    }

    public void setObjectId(Long objectId) {
        this.objectId = objectId;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public void setBeforeValue(String beforeValue) {
        this.beforeValue = beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }

    public void setAfterValue(String afterValue) {
        this.afterValue = afterValue;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
