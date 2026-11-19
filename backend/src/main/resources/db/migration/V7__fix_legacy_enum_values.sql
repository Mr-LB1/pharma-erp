-- V7：清洗 supplier/customer/product 存量脏枚举数据（DML 订正，非 DDL）
-- 背景：V6 仅清洗了 warehouse.temp_type，e2e 联调发现 supplier/customer/product 仍有中文/旧值残留，
--       编辑存量记录保存时会触发 400 枚举校验（前端兜底仅保证列表不崩）。
-- 现状 DISTINCT（清洗前实测）：
--   supplier.settle_type        : 月结 / 现结
--   customer.credit_level       : A / AA
--   product.storage_cond        : 常温 / 阴凉
--   supplier/customer/product 的 first_audit_status、status 与 product.rx_category 均已合法，无需订正。
-- 以下按冻结码表语义映射：
--   settleType  : 月结→MONTHLY、现结→CASH、赊账/账期/信用→CREDIT
--   creditLevel : AA→A（旧「AA」等级无对应码值，按语义收敛为 A）
--   storageCond : 常温→ROOM_TEMP、阴凉→COOL、冷藏→COLD

-- supplier.settle_type
UPDATE supplier SET settle_type = 'MONTHLY'  WHERE settle_type = '月结';
UPDATE supplier SET settle_type = 'CASH'     WHERE settle_type = '现结';
UPDATE supplier SET settle_type = 'CREDIT'   WHERE settle_type IN ('赊账', '账期', '信用');

-- customer.credit_level
UPDATE customer SET credit_level = 'A' WHERE credit_level = 'AA';

-- product.storage_cond
UPDATE product SET storage_cond = 'ROOM_TEMP' WHERE storage_cond = '常温';
UPDATE product SET storage_cond = 'COOL'      WHERE storage_cond = '阴凉';
UPDATE product SET storage_cond = 'COLD'      WHERE storage_cond = '冷藏';
