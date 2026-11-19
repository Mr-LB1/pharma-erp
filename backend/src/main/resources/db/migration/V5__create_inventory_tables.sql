-- V5：批次库存 + 三张只追加流水表（M2 核心交易的灵魂三张）
-- stock / stock_transaction / lot_trace / cost_ledger 均 append-only 语义：
-- 流水三表只插入不修改、不物理删除（R8）。

-- ============ 批次库存（唯一键 product_id + batch_no + location_id） ============
-- location 简化：location_id = warehouse_id（一仓一位），精细货位留 M3
CREATE TABLE stock (
    id           BIGSERIAL PRIMARY KEY,
    product_id   BIGINT NOT NULL,
    batch_no     VARCHAR(50) NOT NULL,
    produce_date DATE,
    expire_date  DATE NOT NULL,
    location_id  BIGINT NOT NULL,
    quantity     NUMERIC(14, 3) NOT NULL,
    unit_cost    NUMERIC(14, 2) NOT NULL,
    CONSTRAINT uk_stock_product_batch_location UNIQUE (product_id, batch_no, location_id)
);

-- ============ 库存流水（只追加） ============
CREATE TABLE stock_transaction (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    batch_no   VARCHAR(50) NOT NULL,
    op_type    VARCHAR(10) NOT NULL,          -- IN / OUT
    ref_type   VARCHAR(30),
    ref_id     BIGINT,
    quantity   NUMERIC(14, 3) NOT NULL,       -- + 入库 / - 出库
    before_qty NUMERIC(14, 3) NOT NULL,
    after_qty  NUMERIC(14, 3) NOT NULL,
    op_user    VARCHAR(50),
    op_time    TIMESTAMP NOT NULL DEFAULT now()
);

-- ============ 批次追溯（只追加） ============
CREATE TABLE lot_trace (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT NOT NULL,
    batch_no    VARCHAR(50) NOT NULL,
    source_type VARCHAR(30),
    source_id   BIGINT,
    target_type VARCHAR(30),
    target_id   BIGINT,
    quantity    NUMERIC(14, 3) NOT NULL,
    op_time     TIMESTAMP NOT NULL DEFAULT now(),
    op_user     VARCHAR(50)
);

-- ============ 成本台账（只追加，商品级移动加权余额） ============
CREATE TABLE cost_ledger (
    id             BIGSERIAL PRIMARY KEY,
    product_id     BIGINT NOT NULL,
    batch_no       VARCHAR(50),
    op_type        VARCHAR(10) NOT NULL,      -- IN / OUT
    quantity       NUMERIC(14, 3) NOT NULL,
    unit_cost      NUMERIC(14, 2) NOT NULL,
    amount         NUMERIC(14, 2) NOT NULL,   -- + 入库 / - 出库
    balance_qty    NUMERIC(14, 3) NOT NULL,
    balance_amount NUMERIC(14, 2) NOT NULL
);

-- 索引
CREATE INDEX idx_stock_product ON stock (product_id);
CREATE INDEX idx_stock_expire ON stock (product_id, expire_date);
CREATE INDEX idx_stock_transaction_product_batch ON stock_transaction (product_id, batch_no);
CREATE INDEX idx_stock_transaction_ref ON stock_transaction (ref_type, ref_id);
CREATE INDEX idx_lot_trace_product_batch ON lot_trace (product_id, batch_no);
CREATE INDEX idx_cost_ledger_product ON cost_ledger (product_id);
