-- V2：基础资料 + RBAC 权限表
-- 新增：sys_user / sys_role / sys_permission / sys_user_role / sys_role_permission
--       supplier / customer / warehouse
-- 商品表 product 已在 V1 创建。

-- ============ RBAC 权限 ============

CREATE TABLE sys_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL UNIQUE,
    password_bcrypt VARCHAR(100) NOT NULL,
    real_name       VARCHAR(50),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE sys_role (
    id   BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(50) NOT NULL
);

CREATE TABLE sys_permission (
    id   BIGSERIAL PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL REFERENCES sys_user (id),
    role_id BIGINT NOT NULL REFERENCES sys_role (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sys_role_permission (
    role_id       BIGINT NOT NULL REFERENCES sys_role (id),
    permission_id BIGINT NOT NULL REFERENCES sys_permission (id),
    PRIMARY KEY (role_id, permission_id)
);

-- ============ 主数据 ============

CREATE TABLE supplier (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(200) NOT NULL,
    credit_code        VARCHAR(50),
    license_no         VARCHAR(100),
    license_expire     DATE,
    gsp_cert           VARCHAR(100),
    first_audit_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- 首营状态
    settle_type        VARCHAR(20),
    status             VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE customer (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(200) NOT NULL,
    license_no         VARCHAR(100),
    license_expire     DATE,
    credit_level       VARCHAR(20),
    credit_limit       NUMERIC(18, 2),
    first_audit_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- 首营状态
    status             VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE warehouse (
    id        BIGSERIAL PRIMARY KEY,
    name      VARCHAR(100) NOT NULL,
    temp_type VARCHAR(20),                          -- 温控类型：常温/阴凉/冷藏
    status    VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
);

-- 索引
CREATE INDEX idx_product_generic_name ON product (generic_name);
CREATE INDEX idx_supplier_name ON supplier (name);
CREATE INDEX idx_customer_name ON customer (name);
