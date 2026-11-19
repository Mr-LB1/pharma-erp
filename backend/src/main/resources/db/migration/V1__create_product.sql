-- V1：初始化商品主数据表（医药商品，含 GSP 相关字段）
-- 后续每次表结构变更，新增 V2__*.sql、V3__*.sql ...，禁止修改已发布的迁移脚本

CREATE TABLE product (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(50)  NOT NULL UNIQUE,   -- 商品编码
    generic_name    VARCHAR(200) NOT NULL,          -- 通用名
    trade_name      VARCHAR(200),                   -- 商品名
    approval_no     VARCHAR(100),                   -- 批准文号
    dosage_form     VARCHAR(50),                    -- 剂型
    spec            VARCHAR(100),                   -- 规格
    manufacturer    VARCHAR(200),                   -- 生产厂家
    rx_category     VARCHAR(20),                    -- 处方分类：RX/OTC/特殊管理
    storage_cond    VARCHAR(20),                    -- 存储条件：常温/阴凉/冷藏
    first_audit_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- 首营状态
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
