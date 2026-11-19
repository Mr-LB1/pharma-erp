-- V3：RBAC 权限码矩阵初始化（§8.5，冻结）
-- 默认角色 + 全部权限码 + 角色-权限映射。
-- 幂等：角色/权限用 ON CONFLICT DO NOTHING，映射用 WHERE NOT EXISTS，可安全重跑（Flyway 只会执行一次）。

-- ============ 1. 默认角色 ============
INSERT INTO sys_role (code, name) VALUES
    ('ADMIN', '系统管理员'),
    ('PURCHASER', '采购员'),
    ('SALES', '销售员'),
    ('WAREHOUSE', '仓管')
ON CONFLICT (code) DO NOTHING;

-- ============ 2. 权限码（§8.5 全部 35 个） ============
INSERT INTO sys_permission (code, name) VALUES
    -- 商品
    ('product:view', '商品查看'),
    ('product:create', '商品新增'),
    ('product:update', '商品编辑'),
    ('product:delete', '商品删除'),
    -- 供应商
    ('supplier:view', '供应商查看'),
    ('supplier:create', '供应商新增'),
    ('supplier:update', '供应商编辑'),
    ('supplier:delete', '供应商删除'),
    -- 客户
    ('customer:view', '客户查看'),
    ('customer:create', '客户新增'),
    ('customer:update', '客户编辑'),
    ('customer:delete', '客户删除'),
    -- 仓库
    ('warehouse:view', '仓库查看'),
    ('warehouse:create', '仓库新增'),
    ('warehouse:update', '仓库编辑'),
    ('warehouse:delete', '仓库删除'),
    -- 采购
    ('purchase:order:view', '采购订单查看'),
    ('purchase:order:create', '采购订单创建'),
    ('purchase:order:submit', '采购订单提交'),
    ('purchase:receive:view', '采购入库查看'),
    ('purchase:receive:create', '采购入库创建'),
    -- 销售
    ('sales:order:view', '销售订单查看'),
    ('sales:order:create', '销售订单创建'),
    ('sales:order:submit', '销售订单提交'),
    ('sales:delivery:view', '销售出库查看'),
    ('sales:delivery:create', '销售出库创建'),
    -- 库存
    ('inventory:stock:view', '库存查看'),
    -- 系统
    ('system:user:view', '用户查看'),
    ('system:user:create', '用户新增'),
    ('system:user:update', '用户编辑'),
    ('system:user:delete', '用户删除'),
    ('system:role:view', '角色查看'),
    ('system:role:create', '角色新增'),
    ('system:role:update', '角色编辑'),
    ('system:role:delete', '角色删除')
ON CONFLICT (code) DO NOTHING;

-- ============ 3. ADMIN：全部权限 ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============ 4. PURCHASER（采购员） ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'product:view',
    'supplier:view',
    'purchase:order:view',
    'purchase:order:create',
    'purchase:order:submit',
    'purchase:receive:view',
    'purchase:receive:create',
    'inventory:stock:view'
)
WHERE r.code = 'PURCHASER'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============ 5. SALES（销售员） ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'product:view',
    'customer:view',
    'sales:order:view',
    'sales:order:create',
    'sales:order:submit',
    'sales:delivery:view',
    'sales:delivery:create',
    'inventory:stock:view'
)
WHERE r.code = 'SALES'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============ 6. WAREHOUSE（仓管） ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'product:view',
    'warehouse:view',
    'purchase:receive:view',
    'purchase:receive:create',
    'sales:delivery:view',
    'sales:delivery:create',
    'inventory:stock:view'
)
WHERE r.code = 'WAREHOUSE'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
