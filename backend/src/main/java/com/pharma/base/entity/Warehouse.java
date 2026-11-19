package com.pharma.base.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

/**
 * 仓库主数据。
 */
@Entity
@Table(name = "warehouse")
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "仓库名称不能为空")
    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "temp_type", length = 20)
    private String tempType;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = "ACTIVE";
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTempType() {
        return tempType;
    }

    public void setTempType(String tempType) {
        this.tempType = tempType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
