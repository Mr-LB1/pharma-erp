-- V9：P2 基础报表 + 品种级近效期阈值 + 不合格品管理 + 遗留修复
-- 1) product 加 near_expiry_days（品种近效期阈值，NULL=用全局 30 天）
-- 2) stocktake_item 加 produce_date / expire_date（盘盈新批次建库存用，可空）
-- 3) 新增 unqualified（不合格品登记）表
-- 4) 追加 P2 权限码 + ADMIN 全量映射

-- ============ 1. 品种近效期阈值 ============
ALTER TABLE product ADD COLUMN IF NOT EXISTS near_expiry_days INT NULL;

-- ============ 2. 盘点明细补充生产/效期日期（盘盈新批次） ============
ALTER TABLE stocktake_item ADD COLUMN IF NOT EXISTS produce_date DATE NULL;
ALTER TABLE stocktake_item ADD COLUMN IF NOT EXISTS expire_date  DATE NULL;

-- ============ 3. 不合格品登记 ============
CREATE TABLE IF NOT EXISTS unqualified (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT        NOT NULL REFERENCES product (id),
    batch_no   VARCHAR(50)   NOT NULL,
    quantity   NUMERIC(14, 3) NOT NULL,
    reason     VARCHAR(500),
    status     VARCHAR(20)   NOT NULL DEFAULT 'PENDING',   -- PENDING / DISPOSED
    created_by VARCHAR(50),
    created_at TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_unqualified_product ON unqualified (product_id, batch_no);

-- ============ 4. P2 权限码 ============
INSERT INTO sys_permission (code, name) VALUES
    ('report:view', '报表查看'),
    ('quality:unqualified:view', '不合格品查看'),
    ('quality:unqualified:create', '不合格品登记'),
    ('quality:unqualified:dispose', '不合格品处理')
ON CONFLICT (code) DO NOTHING;

-- ============ 5. ADMIN 全量权限（含新增 P2 权限码） ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
