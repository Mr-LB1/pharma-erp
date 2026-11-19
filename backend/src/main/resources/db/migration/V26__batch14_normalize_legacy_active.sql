-- V26：批次十四 历史状态归一（前端状态筛选/统计口径一致）
-- 4 类单据历史数据 status='ACTIVE'（旧实现「建单即执行」）统一归一为 'COMPLETED'（新状态机终态）。
-- 仅归一执行完成的旧状态，不触碰 DRAFT/SUBMITTED/APPROVED/PUSHED/REJECTED/VOID。
-- 幂等：重复执行不会再命中（WHERE status='ACTIVE'）。

UPDATE purchase_receive SET status = 'COMPLETED' WHERE status = 'ACTIVE';
UPDATE sales_delivery   SET status = 'COMPLETED' WHERE status = 'ACTIVE';
UPDATE purchase_return  SET status = 'COMPLETED' WHERE status = 'ACTIVE';
UPDATE stock_transfer   SET status = 'COMPLETED' WHERE status = 'ACTIVE';
