-- V11：批次一订单生命周期权限码（P0-3）
-- 新增：purchase:order:update / purchase:order:void / sales:order:update / sales:order:void
-- 幂等：权限码 ON CONFLICT DO NOTHING，映射 WHERE NOT EXISTS，可安全重跑。

-- ============ 1. 权限码 ============
INSERT INTO sys_permission (code, name) VALUES
    ('purchase:order:update', '采购订单编辑'),
    ('purchase:order:void',   '采购订单作废'),
    ('sales:order:update',    '销售订单编辑'),
    ('sales:order:void',      '销售订单作废')
ON CONFLICT (code) DO NOTHING;

-- ============ 2. ADMIN：全部新增权限 ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'purchase:order:update',
    'purchase:order:void',
    'sales:order:update',
    'sales:order:void'
)
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============ 3. PURCHASER：采购订单编辑/作废（草稿与已提交单据的日常维护） ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'purchase:order:update',
    'purchase:order:void'
)
WHERE r.code = 'PURCHASER'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============ 4. SALES：销售订单编辑/作废 ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'sales:order:update',
    'sales:order:void'
)
WHERE r.code = 'SALES'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
