-- V17：成本台账补时间维度（#25 报表金额按时间窗口聚合）
-- cost_ledger 原设计只有 append-only 流水、无时间列；报表期初/入库/出库/期末金额需按 op_time 切窗。
-- 1) 加列（先允许 NULL，便于历史回填）
-- 2) 历史行尽力回填：按 product_id 内 id 序号与 stock_transaction 对齐取 op_time（两者同事务 1:1 写入）
-- 3) 兜底 now() → 设默认值与非空约束
-- 4) 索引 (product_id, op_time)
-- 幂等可重跑。
ALTER TABLE cost_ledger ADD COLUMN IF NOT EXISTS op_time TIMESTAMP;

WITH tx AS (
    SELECT id, product_id, op_time,
           ROW_NUMBER() OVER (PARTITION BY product_id ORDER BY id) AS rn
    FROM stock_transaction
), cl AS (
    SELECT id, product_id,
           ROW_NUMBER() OVER (PARTITION BY product_id ORDER BY id) AS rn
    FROM cost_ledger
    WHERE op_time IS NULL
)
UPDATE cost_ledger c
SET op_time = tx.op_time
FROM cl
JOIN tx ON tx.product_id = cl.product_id AND tx.rn = cl.rn
WHERE c.id = cl.id;

UPDATE cost_ledger SET op_time = now() WHERE op_time IS NULL;

ALTER TABLE cost_ledger ALTER COLUMN op_time SET DEFAULT now();
ALTER TABLE cost_ledger ALTER COLUMN op_time SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cost_ledger_product_time ON cost_ledger (product_id, op_time);
