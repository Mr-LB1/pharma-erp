-- V12：批次二数据一致性底座（软删除 / 单号序列 / 流水仓库 / 盘点提交快照 / 不合格品仓库与销毁留痕）
-- 只做加法，不改 V1~V11；全部语句幂等可重跑。
-- 1) number_sequence：单据号序列（替代时间戳+随机，保证唯一且可读）
-- 2) stock_transaction.location_id：库存流水补仓库维度（按 stock 回填，孤儿流水回填默认仓）
-- 3) stocktake_item.committed_book_qty / committed_diff_qty：提交时点重算的账面与盈亏快照
-- 4) unqualified.warehouse_id / dispose_method / disposed_at / disposed_by：限定仓库 + 销毁留痕

-- ============ 1. 单号序列表 ============
CREATE TABLE IF NOT EXISTS number_sequence (
    prefix VARCHAR(20) PRIMARY KEY,
    seq    BIGINT      NOT NULL DEFAULT 0
);

-- ============ 2. 库存流水补仓库维度 ============
ALTER TABLE stock_transaction ADD COLUMN IF NOT EXISTS location_id BIGINT;

-- 2.1 按库存批次回填（同一 product_id + batch_no 的批次所属仓库）
UPDATE stock_transaction t
SET location_id = s.location_id
FROM stock s
WHERE t.location_id IS NULL
  AND s.product_id = t.product_id
  AND s.batch_no = t.batch_no;

-- 2.2 孤儿流水（批次库存已不存在）回填默认仓（id 最小的仓库）
UPDATE stock_transaction
SET location_id = (SELECT MIN(id) FROM warehouse)
WHERE location_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_stock_transaction_location ON stock_transaction (location_id);

-- ============ 3. 盘点提交时点快照 ============
ALTER TABLE stocktake_item ADD COLUMN IF NOT EXISTS committed_book_qty NUMERIC(14, 3);
ALTER TABLE stocktake_item ADD COLUMN IF NOT EXISTS committed_diff_qty NUMERIC(14, 3);

-- 历史已完成盘点：提交值即创建时值
UPDATE stocktake_item si
SET committed_book_qty = si.book_qty,
    committed_diff_qty = si.diff_qty
FROM stocktake st
WHERE si.stocktake_id = st.id
  AND st.status = 'COMPLETED'
  AND si.committed_book_qty IS NULL;

-- ============ 4. 不合格品：仓库 + 销毁留痕 ============
ALTER TABLE unqualified ADD COLUMN IF NOT EXISTS warehouse_id BIGINT;
ALTER TABLE unqualified ADD COLUMN IF NOT EXISTS dispose_method VARCHAR(50);
ALTER TABLE unqualified ADD COLUMN IF NOT EXISTS disposed_at TIMESTAMP;
ALTER TABLE unqualified ADD COLUMN IF NOT EXISTS disposed_by VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_unqualified_warehouse ON unqualified (warehouse_id);
