-- V23：批次十三决策（退货专员作废权限 / L2 分级配独立角色）
-- 1) 退货专员追加销售/采购退货作废权限（sales:return:void 缺失则先补建并授 ADMIN）
-- 2) 新增高级审批人 SENIOR_APPROVER，与 APPROVER 同款审批权限
-- 3) approval_config 的 L2 记录改由 SENIOR_APPROVER 审批（L1 保持 APPROVER）
-- 全部语句幂等可重跑。

-- ============ 1. 退货作废权限 ============
-- purchase:return:void 由 V21 建立；sales:return:void 首次建立（此前无销售退货作废能力）
INSERT INTO sys_permission (code, name) VALUES
    ('sales:return:void', '销售退货作废')
ON CONFLICT (code) DO NOTHING;

-- ADMIN：授予销售退货作废（采购退货作废 V21 已授，此处兼容重跑）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN ('sales:return:void', 'purchase:return:void')
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 退货专员：授予销售/采购退货作废（可作废他人退货单）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN ('sales:return:void', 'purchase:return:void')
WHERE r.code = 'RETURN_SPECIALIST'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============ 2. 高级审批人角色 ============
INSERT INTO sys_role (code, name) VALUES
    ('SENIOR_APPROVER', '高级审批人')
ON CONFLICT (code) DO NOTHING;

-- 与 APPROVER 同款权限：审批中心查看/审批 + 采购/销售订单查看
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'approval:view',
    'approval:approve',
    'purchase:order:view',
    'sales:order:view'
)
WHERE r.code = 'SENIOR_APPROVER'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============ 3. L2 改由高级审批人审批 ============
-- L1（0~99999.99）保持 APPROVER；L2（>=100000）改 SENIOR_APPROVER。
-- 条件限定 level=2 AND approver_role='APPROVER'，重跑不会覆盖运维后续手工调整。
UPDATE approval_config
SET approver_role = 'SENIOR_APPROVER'
WHERE level = 2
  AND approver_role = 'APPROVER';
