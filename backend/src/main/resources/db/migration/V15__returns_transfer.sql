-- V15：批次四 销售退货 / 采购退货 / 仓库调拨 + 财务与退货调拨权限码
-- 只做加法，不改 V1~V14；全部语句幂等可重跑。
-- 1) sales_return / sales_return_item：销售退货（DRAFT → SUBMITTED → APPROVED → RECEIVED），验收分流
-- 2) purchase_return / purchase_return_item：采购退货（精确批次扣减 + 应付红冲）
-- 3) stock_transfer / stock_transfer_item：仓库调拨（一减一增两条流水）
-- 4) 权限码：财务 AR/AP/收付款/对账/发票 + 退货 + 调拨；FINANCE 角色；ADMIN 全量；业务角色补配

-- ============ 1. 销售退货 ============
CREATE TABLE IF NOT EXISTS sales_return (
    id           BIGSERIAL PRIMARY KEY,
    return_no    VARCHAR(50) NOT NULL UNIQUE,
    delivery_id  BIGINT      NOT NULL,              -- 原销售出库单
    customer_id  BIGINT      NOT NULL,
    store_id     BIGINT,                            -- 门店退货来源（可空）
    return_date  DATE        NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT', -- DRAFT/SUBMITTED/APPROVED/REJECTED/RECEIVED/VOID
    total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    reason       VARCHAR(500),
    created_by   VARCHAR(50),
    created_at   TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sales_return_delivery ON sales_return (delivery_id);
CREATE INDEX IF NOT EXISTS idx_sales_return_customer ON sales_return (customer_id);

CREATE TABLE IF NOT EXISTS sales_return_item (
    id            BIGSERIAL PRIMARY KEY,
    return_id     BIGINT        NOT NULL,
    product_id    BIGINT        NOT NULL,
    batch_no      VARCHAR(50)   NOT NULL,
    quantity      NUMERIC(14, 3) NOT NULL,
    unit_price    NUMERIC(18, 2) NOT NULL DEFAULT 0,
    unit_cost     NUMERIC(18, 2) NOT NULL DEFAULT 0,
    warehouse_id  BIGINT        NOT NULL,           -- 退回入库仓库
    accept_result VARCHAR(20),                      -- QUALIFIED / UNQUALIFIED，验收时写入
    reject_reason VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_sales_return_item_return ON sales_return_item (return_id);

-- ============ 2. 采购退货 ============
CREATE TABLE IF NOT EXISTS purchase_return (
    id           BIGSERIAL PRIMARY KEY,
    return_no    VARCHAR(50) NOT NULL UNIQUE,
    receive_id   BIGINT,                            -- 原采购入库单（可空，允许直接退货）
    supplier_id  BIGINT      NOT NULL,
    return_date  DATE        NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    reason       VARCHAR(500),
    created_by   VARCHAR(50),
    created_at   TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_purchase_return_supplier ON purchase_return (supplier_id);

CREATE TABLE IF NOT EXISTS purchase_return_item (
    id           BIGSERIAL PRIMARY KEY,
    return_id    BIGINT        NOT NULL,
    product_id   BIGINT        NOT NULL,
    batch_no     VARCHAR(50)   NOT NULL,
    quantity     NUMERIC(14, 3) NOT NULL,
    unit_cost    NUMERIC(18, 2) NOT NULL DEFAULT 0,
    warehouse_id BIGINT        NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_purchase_return_item_return ON purchase_return_item (return_id);

-- ============ 3. 仓库调拨 ============
CREATE TABLE IF NOT EXISTS stock_transfer (
    id                BIGSERIAL PRIMARY KEY,
    transfer_no       VARCHAR(50) NOT NULL UNIQUE,
    from_warehouse_id BIGINT      NOT NULL,
    to_warehouse_id   BIGINT      NOT NULL,
    transfer_date     DATE        NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    remark            VARCHAR(500),
    created_by        VARCHAR(50),
    created_at        TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_stock_transfer_from ON stock_transfer (from_warehouse_id);
CREATE INDEX IF NOT EXISTS idx_stock_transfer_to ON stock_transfer (to_warehouse_id);

CREATE TABLE IF NOT EXISTS stock_transfer_item (
    id          BIGSERIAL PRIMARY KEY,
    transfer_id BIGINT        NOT NULL,
    product_id  BIGINT        NOT NULL,
    batch_no    VARCHAR(50)   NOT NULL,
    quantity    NUMERIC(14, 3) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_stock_transfer_item_transfer ON stock_transfer_item (transfer_id);

-- ============ 4. 权限码 ============
INSERT INTO sys_permission (code, name) VALUES
    ('finance:ar:view', '应收查看'),
    ('finance:ar:create', '应收补录'),
    ('finance:ap:view', '应付查看'),
    ('finance:ap:create', '应付补录'),
    ('finance:receipt:view', '收款单查看'),
    ('finance:receipt:create', '收款核销'),
    ('finance:payment:view', '付款单查看'),
    ('finance:payment:create', '付款核销'),
    ('finance:statement:view', '客户对账查看'),
    ('finance:invoice:view', '发票登记查看'),
    ('finance:invoice:create', '发票登记'),
    ('sales:return:view', '销售退货查看'),
    ('sales:return:create', '销售退货创建'),
    ('sales:return:receive', '销售退货验收'),
    ('purchase:return:view', '采购退货查看'),
    ('purchase:return:create', '采购退货创建'),
    ('inventory:transfer:view', '仓库调拨查看'),
    ('inventory:transfer:create', '仓库调拨创建')
ON CONFLICT (code) DO NOTHING;

-- 4.1 FINANCE 角色 + 权限
INSERT INTO sys_role (code, name) VALUES
    ('FINANCE', '财务')
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'finance:ar:view',
    'finance:ar:create',
    'finance:ap:view',
    'finance:ap:create',
    'finance:receipt:view',
    'finance:receipt:create',
    'finance:payment:view',
    'finance:payment:create',
    'finance:statement:view',
    'finance:invoice:view',
    'finance:invoice:create',
    'report:view',
    'customer:view',
    'supplier:view',
    'sales:delivery:view',
    'purchase:receive:view'
)
WHERE r.code = 'FINANCE'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 4.2 SALES：销售退货
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN ('sales:return:view', 'sales:return:create')
WHERE r.code = 'SALES'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 4.3 PURCHASER：采购退货
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN ('purchase:return:view', 'purchase:return:create')
WHERE r.code = 'PURCHASER'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 4.4 WAREHOUSE：调拨 + 退货查看/验收
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'inventory:transfer:view',
    'inventory:transfer:create',
    'sales:return:view',
    'sales:return:receive',
    'purchase:return:view'
)
WHERE r.code = 'WAREHOUSE'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 4.5 QUALITY：退货验收
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN ('sales:return:view', 'sales:return:receive')
WHERE r.code = 'QUALITY'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 4.6 ADMIN 全量权限（含 V15 新增）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
