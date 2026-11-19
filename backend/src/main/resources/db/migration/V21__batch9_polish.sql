-- V21：批次九打磨（P2-10 首营本人回避 / P2-11 调拨、采购退货作废权限）
-- 幂等可重跑。

-- 1. 主数据创建人：首营审核「本人回避」依据
ALTER TABLE product ADD COLUMN IF NOT EXISTS created_by VARCHAR(50);
ALTER TABLE supplier ADD COLUMN IF NOT EXISTS created_by VARCHAR(50);
ALTER TABLE customer ADD COLUMN IF NOT EXISTS created_by VARCHAR(50);

-- 2. 新增作废权限码
INSERT INTO sys_permission (code, name) VALUES
    ('inventory:transfer:void', '仓库调拨作废'),
    ('purchase:return:void', '采购退货作废')
ON CONFLICT (code) DO NOTHING;

-- 3. ADMIN 授予新增权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN ('inventory:transfer:void', 'purchase:return:void')
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 4. WAREHOUSE：调拨作废
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code = 'inventory:transfer:void'
WHERE r.code = 'WAREHOUSE'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 5. PURCHASER：采购退货作废
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code = 'purchase:return:void'
WHERE r.code = 'PURCHASER'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
