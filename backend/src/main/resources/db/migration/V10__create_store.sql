-- V10：门店主数据（Store）+ 权限码
-- ERP 为门店主数据源，门店通过「预留事件」同步至 POS（STORE_MASTER_UPSERT），本脚本仅建表与授权。
-- 幂等：权限码 ON CONFLICT DO NOTHING，角色映射 WHERE NOT EXISTS。

-- ============ 1. 门店表 ============
CREATE TABLE store (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(50)  NOT NULL UNIQUE,                    -- 门店编码
    name           VARCHAR(200) NOT NULL,                           -- 门店名称
    type           VARCHAR(20)  NOT NULL DEFAULT 'DIRECT',          -- DIRECT 直营 / FRANCHISE 加盟
    warehouse_id   BIGINT       NOT NULL REFERENCES warehouse (id), -- 必须关联仓库
    address        VARCHAR(500),
    contact_name   VARCHAR(50),
    contact_phone  VARCHAR(50),
    license_no     VARCHAR(100),                                    -- 药品经营许可证号
    license_expire DATE,                                            -- 许可证到期
    config         JSONB,                                           -- POS 需要的门店配置（营业时间/支付/政策等，预留）
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP
);

-- ============ 2. 索引 ============
CREATE INDEX idx_store_warehouse ON store (warehouse_id);
CREATE INDEX idx_store_license_expire ON store (license_expire);

-- ============ 3. 权限码 ============
INSERT INTO sys_permission (code, name) VALUES
    ('base:store:view', '门店查看'),
    ('base:store:create', '门店新增'),
    ('base:store:update', '门店编辑'),
    ('base:store:delete', '门店删除')
ON CONFLICT (code) DO NOTHING;

-- ============ 4. ADMIN 全量权限（含新增门店权限码） ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
