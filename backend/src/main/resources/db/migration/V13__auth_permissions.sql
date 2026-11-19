-- V13：批次三 认证安全 + 权限补配（P0-6 / P1-21 / P1-22 / P1-23 / P1-24）
-- 只做加法，不改 V1~V12；全部语句幂等可重跑。
-- 1) sys_user.token_version：JWT 版本号，禁用/改密/登出即 +1，旧 token 立即失效
-- 2) 新增权限码 inventory:cost:view（成本查看）
-- 3) 新增 QUALITY 角色（首营审核 / 证照预警 / 不合格品 / 追溯）
-- 4) WAREHOUSE 补配盘点权限 + 成本查看权限
-- 5) ADMIN 全量映射（含新增权限码）

-- ============ 1. 用户 token 版本号 ============
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS token_version INT NOT NULL DEFAULT 1;

-- ============ 2. 权限码 ============
INSERT INTO sys_permission (code, name) VALUES
    ('inventory:cost:view', '库存成本查看')
ON CONFLICT (code) DO NOTHING;

-- ============ 3. QUALITY 角色 ============
INSERT INTO sys_role (code, name) VALUES
    ('QUALITY', '质量管理')
ON CONFLICT (code) DO NOTHING;

-- 3.1 QUALITY 权限：首营审核、证照预警、不合格品、追溯 + 必要基础查看
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'product:view',
    'supplier:view',
    'customer:view',
    'inventory:stock:view',
    'quality:first-audit:view',
    'quality:first-audit:approve',
    'quality:license:view',
    'quality:unqualified:view',
    'quality:unqualified:create',
    'quality:unqualified:dispose',
    'trace:view'
)
WHERE r.code = 'QUALITY'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============ 4. WAREHOUSE 补配：盘点 + 成本查看 ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'inventory:stocktake:view',
    'inventory:stocktake:create',
    'inventory:stocktake:submit',
    'inventory:cost:view'
)
WHERE r.code = 'WAREHOUSE'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============ 5. ADMIN 全量权限（含 V11~V13 新增） ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
