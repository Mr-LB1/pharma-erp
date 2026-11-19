-- V25：批次十四 单据审批后的 WMS 下发与回传（DRAFT → SUBMITTED → APPROVED → PUSHED → COMPLETED）
-- 1) 4 张单据主表：WMS 下发/回传信息
-- 2) 4 张明细表：WMS 回传实量与实际差异留痕
-- 3) WMS 回传权限码
-- 全部语句幂等可重跑。

-- ============ 1. 单据主表：WMS 信息 ============
ALTER TABLE purchase_receive ADD COLUMN IF NOT EXISTS wms_pushed_at   TIMESTAMP;
ALTER TABLE purchase_receive ADD COLUMN IF NOT EXISTS wms_no          VARCHAR(100);
ALTER TABLE purchase_receive ADD COLUMN IF NOT EXISTS wms_callback_at TIMESTAMP;

ALTER TABLE sales_delivery   ADD COLUMN IF NOT EXISTS wms_pushed_at   TIMESTAMP;
ALTER TABLE sales_delivery   ADD COLUMN IF NOT EXISTS wms_no          VARCHAR(100);
ALTER TABLE sales_delivery   ADD COLUMN IF NOT EXISTS wms_callback_at TIMESTAMP;

ALTER TABLE purchase_return  ADD COLUMN IF NOT EXISTS wms_pushed_at   TIMESTAMP;
ALTER TABLE purchase_return  ADD COLUMN IF NOT EXISTS wms_no          VARCHAR(100);
ALTER TABLE purchase_return  ADD COLUMN IF NOT EXISTS wms_callback_at TIMESTAMP;

ALTER TABLE stock_transfer   ADD COLUMN IF NOT EXISTS wms_pushed_at   TIMESTAMP;
ALTER TABLE stock_transfer   ADD COLUMN IF NOT EXISTS wms_no          VARCHAR(100);
ALTER TABLE stock_transfer   ADD COLUMN IF NOT EXISTS wms_callback_at TIMESTAMP;

-- ============ 2. 明细表：实量 + 差异 ============
ALTER TABLE purchase_receive_item ADD COLUMN IF NOT EXISTS actual_qty NUMERIC(14, 3);
ALTER TABLE purchase_receive_item ADD COLUMN IF NOT EXISTS qty_diff   NUMERIC(14, 3);

ALTER TABLE sales_delivery_item   ADD COLUMN IF NOT EXISTS actual_qty NUMERIC(14, 3);
ALTER TABLE sales_delivery_item   ADD COLUMN IF NOT EXISTS qty_diff   NUMERIC(14, 3);

ALTER TABLE purchase_return_item  ADD COLUMN IF NOT EXISTS actual_qty NUMERIC(14, 3);
ALTER TABLE purchase_return_item  ADD COLUMN IF NOT EXISTS qty_diff   NUMERIC(14, 3);

ALTER TABLE stock_transfer_item   ADD COLUMN IF NOT EXISTS actual_qty NUMERIC(14, 3);
ALTER TABLE stock_transfer_item   ADD COLUMN IF NOT EXISTS qty_diff   NUMERIC(14, 3);

-- ============ 3. WMS 回传权限码 ============
INSERT INTO sys_permission (code, name) VALUES
    ('purchase:receive:wms-callback', '采购入库WMS回传'),
    ('sales:delivery:wms-callback', '销售出库WMS回传'),
    ('purchase:return:wms-callback', '采购退货WMS回传'),
    ('inventory:transfer:wms-callback', '仓库调拨WMS回传')
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'purchase:receive:wms-callback', 'sales:delivery:wms-callback',
    'purchase:return:wms-callback', 'inventory:transfer:wms-callback')
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'purchase:receive:wms-callback', 'sales:delivery:wms-callback', 'inventory:transfer:wms-callback')
WHERE r.code = 'WAREHOUSE'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
