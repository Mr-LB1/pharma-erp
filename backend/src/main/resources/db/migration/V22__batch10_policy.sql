-- V22：批次十策略调整
-- 1) 新增退货专员角色（退货/调拨不再按 createdBy 隔离，改由角色权限控制）
-- 2) receipt.receipt_date：对账单按业务单据日期分期（历史回填 created_at 日期）
-- 3) 首营 created_by 历史回填（无法确定创建人时回填 'system'，保证自审校验全覆盖）
-- 全部语句幂等可重跑。

-- ============ 1. 退货专员角色 ============
INSERT INTO sys_role (code, name) VALUES
    ('RETURN_SPECIALIST', '退货专员')
ON CONFLICT (code) DO NOTHING;

-- 退货专员：销售/采购退货查看、创建、销售退货验收 + 库存/主数据只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'sales:return:view',
    'sales:return:create',
    'sales:return:receive',
    'purchase:return:view',
    'purchase:return:create',
    'inventory:stock:view',
    'product:view',
    'customer:view',
    'supplier:view'
)
WHERE r.code = 'RETURN_SPECIALIST'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ============ 2. 收款业务日期 ============
-- 对账单原按 created_at 分期；改为按业务单据日期（出库/退货/收款各自业务日期）。
-- 收款单此前无业务日期字段，此处新增 receipt_date 并以 created_at 日期回填。
ALTER TABLE receipt ADD COLUMN IF NOT EXISTS receipt_date DATE;

UPDATE receipt
SET receipt_date = CAST(created_at AS DATE)
WHERE receipt_date IS NULL;

CREATE INDEX IF NOT EXISTS idx_receipt_customer_date ON receipt (customer_id, receipt_date);

-- ============ 3. 首营 created_by 回填 ============
-- 历史数据（V21 之前创建）无 created_by，导致首营「本人回避」校验被跳过。
-- 现有审计/导入日志只记录批量导入（object_id 为空），无法逐条确定创建人，
-- 故统一回填 'system'（代表历史/系统导入数据）：既不置空，也不会误判为某个真实用户，
-- 从而让审核人无法自审任何历史资料。
UPDATE product  SET created_by = 'system' WHERE created_by IS NULL;
UPDATE supplier SET created_by = 'system' WHERE created_by IS NULL;
UPDATE customer SET created_by = 'system' WHERE created_by IS NULL;
