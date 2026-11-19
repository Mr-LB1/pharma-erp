-- V13.1：正式固化 APPROVER（审批人）角色
-- 背景：库内已存在 APPROVER 角色（前端角色下拉亦使用），但 V3~V13 未定义，全新环境会缺失。
-- 只做加法且幂等：角色已存在则跳过，权限映射按缺补。
-- 版本 13.1 位于 V13 与批次四 V14 之间，不影响后续迁移顺序。

INSERT INTO sys_role (code, name) VALUES
    ('APPROVER', '审批人')
ON CONFLICT (code) DO NOTHING;

-- 审批中心所需权限：查看待审批 + 通过/驳回 + 采购/销售订单查看
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'approval:view',
    'approval:approve',
    'purchase:order:view',
    'sales:order:view'
)
WHERE r.code = 'APPROVER'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
