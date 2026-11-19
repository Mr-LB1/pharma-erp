-- V8：M3 合规闭环（首营审核 + 证照预警 + 批次追溯 + 单据作废 + 审批流 + 审计日志 + 盘点）
-- 新增 4 表：approval_record / audit_log / stocktake / stocktake_item
-- 追加 M3 权限码（§10.4）+ ADMIN 全量映射。
-- 幂等：权限码 ON CONFLICT DO NOTHING，映射 WHERE NOT EXISTS。

-- ============ 1. 审批记录 ============
CREATE TABLE approval_record (
    id         BIGSERIAL PRIMARY KEY,
    biz_type   VARCHAR(30)  NOT NULL,          -- PURCHASE_ORDER / SALES_ORDER / FIRST_AUDIT
    biz_id     BIGINT       NOT NULL,
    node       VARCHAR(30),                    -- 首营对象类型 product/supplier/customer；订单为 null
    approver   VARCHAR(50),
    status     VARCHAR(20)  NOT NULL,          -- PENDING / APPROVED / REJECTED
    comment    VARCHAR(500),
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

-- ============ 2. 审计日志 ============
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50),
    action      VARCHAR(50)  NOT NULL,
    object_type VARCHAR(50),
    object_id   BIGINT,
    detail      VARCHAR(1000),
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- ============ 3. 盘点单 ============
CREATE TABLE stocktake (
    id           BIGSERIAL PRIMARY KEY,
    stocktake_no VARCHAR(50) NOT NULL UNIQUE,
    warehouse_id BIGINT      NOT NULL REFERENCES warehouse (id),
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',   -- DRAFT / COMPLETED
    created_by   VARCHAR(50),
    created_at   TIMESTAMP   NOT NULL DEFAULT now()
);

-- ============ 4. 盘点明细（book=账面，actual=实盘，diff=盈亏=actual-book） ============
CREATE TABLE stocktake_item (
    id           BIGSERIAL PRIMARY KEY,
    stocktake_id BIGINT        NOT NULL REFERENCES stocktake (id),
    product_id   BIGINT        NOT NULL REFERENCES product (id),
    batch_no     VARCHAR(50)   NOT NULL,
    book_qty     NUMERIC(14, 3) NOT NULL DEFAULT 0,
    actual_qty   NUMERIC(14, 3) NOT NULL DEFAULT 0,
    diff_qty     NUMERIC(14, 3) NOT NULL DEFAULT 0
);

-- 索引
CREATE INDEX idx_approval_record_biz ON approval_record (biz_type, biz_id);
CREATE INDEX idx_audit_log_created ON audit_log (created_at);
CREATE INDEX idx_stocktake_warehouse ON stocktake (warehouse_id);
CREATE INDEX idx_stocktake_item_stocktake ON stocktake_item (stocktake_id);

-- ============ 5. M3 权限码（§10.4） ============
INSERT INTO sys_permission (code, name) VALUES
    ('quality:first-audit:view', '首营审核查看'),
    ('quality:first-audit:approve', '首营审核审批'),
    ('quality:license:view', '证照预警查看'),
    ('trace:view', '追溯查询'),
    ('approval:view', '审批查看'),
    ('approval:approve', '审批操作'),
    ('system:audit-log:view', '审计日志查看'),
    ('inventory:stocktake:view', '盘点查看'),
    ('inventory:stocktake:create', '盘点创建'),
    ('inventory:stocktake:submit', '盘点提交'),
    ('purchase:receive:void', '采购入库作废'),
    ('sales:delivery:void', '销售出库作废')
ON CONFLICT (code) DO NOTHING;

-- ============ 6. ADMIN 全量权限（含新增 M3 权限码） ============
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
