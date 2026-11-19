-- V24：批次十四 单据审批流（采购入库 / 销售出库 / 采购退货 / 仓库调拨）
-- 1) 三张单据补 total_amount（审批金额分级与待审列表展示用；purchase_return 已有该列）
-- 2) 四张单据补 status 索引（待审 UNION 查询按 status='SUBMITTED' 过滤）
-- 3) 新增 submit 权限码并授予相关角色
-- 全部语句幂等可重跑。

-- ============ 1. 单据总金额 ============
ALTER TABLE purchase_receive ADD COLUMN IF NOT EXISTS total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0;
ALTER TABLE sales_delivery  ADD COLUMN IF NOT EXISTS total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0;
ALTER TABLE stock_transfer  ADD COLUMN IF NOT EXISTS total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0;

-- 1.1 单据级仓库（审批通过后才执行，仓库必须持久化；历史单据保持 NULL）
ALTER TABLE purchase_receive ADD COLUMN IF NOT EXISTS warehouse_id BIGINT;
ALTER TABLE sales_delivery   ADD COLUMN IF NOT EXISTS warehouse_id BIGINT;

-- 历史已完成单据回填：入库按明细成本合计，出库按原订单单价 × 数量，调拨按批次成本 × 数量
UPDATE purchase_receive r
SET total_amount = COALESCE((
    SELECT SUM(i.quantity * i.unit_cost) FROM purchase_receive_item i WHERE i.receive_id = r.id), 0)
WHERE r.total_amount = 0
  AND EXISTS (SELECT 1 FROM purchase_receive_item i WHERE i.receive_id = r.id);

UPDATE sales_delivery d
SET total_amount = COALESCE((
    SELECT SUM(di.quantity * COALESCE((
        SELECT oi.unit_price FROM sales_order_item oi
        WHERE oi.order_id = d.order_id AND oi.product_id = di.product_id LIMIT 1), 0))
    FROM sales_delivery_item di WHERE di.delivery_id = d.id), 0)
WHERE d.total_amount = 0
  AND EXISTS (SELECT 1 FROM sales_delivery_item di WHERE di.delivery_id = d.id);

UPDATE stock_transfer t
SET total_amount = COALESCE((
    SELECT SUM(ti.quantity * COALESCE((
        SELECT s.unit_cost FROM stock s
        WHERE s.product_id = ti.product_id AND s.batch_no = ti.batch_no
          AND s.location_id = t.from_warehouse_id LIMIT 1), 0))
    FROM stock_transfer_item ti WHERE ti.transfer_id = t.id), 0)
WHERE t.total_amount = 0
  AND EXISTS (SELECT 1 FROM stock_transfer_item ti WHERE ti.transfer_id = t.id);

-- ============ 2. 状态索引 ============
CREATE INDEX IF NOT EXISTS idx_purchase_receive_status ON purchase_receive (status);
CREATE INDEX IF NOT EXISTS idx_sales_delivery_status ON sales_delivery (status);
CREATE INDEX IF NOT EXISTS idx_purchase_return_status ON purchase_return (status);
CREATE INDEX IF NOT EXISTS idx_stock_transfer_status ON stock_transfer (status);

-- ============ 3. 提交权限码 ============
INSERT INTO sys_permission (code, name) VALUES
    ('purchase:receive:submit', '采购入库提交审批'),
    ('sales:delivery:submit', '销售出库提交审批'),
    ('purchase:return:submit', '采购退货提交审批'),
    ('inventory:transfer:submit', '仓库调拨提交审批')
ON CONFLICT (code) DO NOTHING;

-- 3.1 ADMIN 全量
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'purchase:receive:submit', 'sales:delivery:submit',
    'purchase:return:submit', 'inventory:transfer:submit')
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 3.2 WAREHOUSE：入库 / 出库 / 调拨提交
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'purchase:receive:submit', 'sales:delivery:submit', 'inventory:transfer:submit')
WHERE r.code = 'WAREHOUSE'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 3.3 PURCHASER：入库 / 退货提交
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN ('purchase:receive:submit', 'purchase:return:submit')
WHERE r.code = 'PURCHASER'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 3.4 SALES：出库提交
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code = 'sales:delivery:submit'
WHERE r.code = 'SALES'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 3.5 RETURN_SPECIALIST：退货提交（销售退货沿用原流程，这里补采购退货）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code = 'purchase:return:submit'
WHERE r.code = 'RETURN_SPECIALIST'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
