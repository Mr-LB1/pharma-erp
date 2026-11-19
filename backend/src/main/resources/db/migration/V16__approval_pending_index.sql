-- V16：待审批分页索引（#27）
-- 三个业务表按 status 过滤 + id 排序的 UNION ALL 分页查询，补 (status, id) 复合索引。
-- 只做加法，幂等可重跑。
CREATE INDEX IF NOT EXISTS idx_purchase_order_status_id ON purchase_order (status, id);
CREATE INDEX IF NOT EXISTS idx_sales_order_status_id ON sales_order (status, id);
CREATE INDEX IF NOT EXISTS idx_sales_return_status_id ON sales_return (status, id);
