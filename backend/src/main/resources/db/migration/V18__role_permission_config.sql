-- V18：角色权限配置（管理员可给角色增删权限）
-- 新增权限码 system:role:config，仅授予 ADMIN；幂等可重跑。

INSERT INTO sys_permission (code, name) VALUES
    ('system:role:config', '角色权限配置')
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code = 'system:role:config'
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
