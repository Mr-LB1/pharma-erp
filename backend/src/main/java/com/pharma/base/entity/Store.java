package com.pharma.base.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 门店主数据。
 * 门店在 ERP 建档后通过 StoreChangedEvent 同步给 POS。
 */
@Entity
@Table(name = "store")
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "门店编码不能为空")
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @NotBlank(message = "门店名称不能为空")
    @Column(nullable = false, length = 200)
    private String name;

    /** DIRECT 直营 / FRANCHISE 加盟。默认值在 prePersist 中补齐（不在此处赋值，避免局部更新时被默认值覆盖）。 */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Column(length = 500)
    private String address;

    @Column(name = "contact_name", length = 50)
    private String contactName;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    /** 药品经营许可证号 */
    @Column(name = "license_no", length = 100)
    private String licenseNo;

    /** 许可证到期日 */
    @Column(name = "license_expire")
    private LocalDate licenseExpire;

    /** POS 需要的门店配置（营业时间/支付/政策等，预留）；须显式映射 jsonb，否则 ddl-auto=validate 启动失败 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 关联仓库名称，非持久化字段，由服务层按 warehouseId 填充后返回给前端。 */
    @Transient
    private String warehouseName;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (type == null || type.isBlank()) {
            type = "DIRECT";
        }
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getLicenseNo() {
        return licenseNo;
    }

    public void setLicenseNo(String licenseNo) {
        this.licenseNo = licenseNo;
    }

    public LocalDate getLicenseExpire() {
        return licenseExpire;
    }

    public void setLicenseExpire(LocalDate licenseExpire) {
        this.licenseExpire = licenseExpire;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getWarehouseName() {
        return warehouseName;
    }

    public void setWarehouseName(String warehouseName) {
        this.warehouseName = warehouseName;
    }
}
