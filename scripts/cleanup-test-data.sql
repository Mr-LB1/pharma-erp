-- =============================================================================
-- pharma-erp 测试数据清理脚本（开发/演示环境专用）
--
-- 用途：清掉冒烟测试残留，保留系统配置与一套演示基础档案。
-- 执行前必须先备份：
--   pg_dump -h localhost -U postgres -Fc -d pharma_erp -f backups\pharma_erp_before_cleanup.dump
-- 还原：
--   pg_restore -h localhost -U postgres -d pharma_erp --clean --if-exists backups\xxx.dump
--
-- 保留：
--   系统：sys_user(admin/approver)、sys_role、sys_permission、sys_role_permission、
--         sys_user_role、approval_config、flyway_schema_history
--   档案：supplier「国药控股有限公司」、customer「北京同仁堂药店」、
--         product「P001」「P-T1795022722」、warehouse「常温库A」
-- 清空：
--   全部业务单据与痕迹（采购/销售/收货/出库/退货/调拨/盘点/库存/流水/成本台账/
--   追溯/审批记录/审计日志/收发发票/收付款/发票登记/不合格品）
--   以及上述保留名单之外的所有测试账号、供应商、客户、商品、仓库
--
-- 说明：本项目 GSP 规则要求业务单据不做物理删除，该脚本只允许在开发/演示库执行。
-- =============================================================================

\encoding UTF8
\set ON_ERROR_STOP on

BEGIN;

-- 1) 业务数据：整表清空并重置自增序列（CASCADE 只影响业务子表，不会动主数据表）
TRUNCATE TABLE
    ap_invoice,
    approval_record,
    ar_invoice,
    audit_log,
    cost_ledger,
    invoice_register,
    lot_trace,
    payment,
    payment_item,
    purchase_order,
    purchase_order_item,
    purchase_receive,
    purchase_receive_item,
    purchase_return,
    purchase_return_item,
    receipt,
    receipt_item,
    sales_delivery,
    sales_delivery_item,
    sales_order,
    sales_order_item,
    sales_return,
    sales_return_item,
    stock,
    stock_transaction,
    stock_transfer,
    stock_transfer_item,
    stocktake,
    stocktake_item,
    unqualified
RESTART IDENTITY CASCADE;

-- 2) 演示仓库：只保留「常温库A」
DELETE FROM warehouse WHERE name <> '常温库A';

-- 3) 演示供应商：只保留「国药控股有限公司」
DELETE FROM supplier WHERE name <> '国药控股有限公司';

-- 4) 演示客户：只保留「北京同仁堂药店」
DELETE FROM customer WHERE name <> '北京同仁堂药店';

-- 5) 演示商品：只保留 P001（阿莫西林/阿莫仙）与 P-T1795022722（阿莫西林胶囊）
DELETE FROM product WHERE code NOT IN ('P001', 'P-T1795022722');

-- 6) 测试账号：只保留 admin 与 approver（及其用户-角色映射）
DELETE FROM sys_user_role
 WHERE user_id IN (SELECT id FROM sys_user WHERE username NOT IN ('admin', 'approver'));
DELETE FROM sys_user WHERE username NOT IN ('admin', 'approver');

-- 7) 单号序列归零，后续单据从 1 开始编号
UPDATE number_sequence SET seq = 0;

COMMIT;

-- ============================ 清理结果核对 ============================
SELECT '业务数据' AS 分类,
       (SELECT count(*) FROM purchase_order) + (SELECT count(*) FROM sales_order) AS 单据数,
       (SELECT count(*) FROM stock) AS 库存数,
       (SELECT count(*) FROM stock_transaction) AS 流水数,
       (SELECT count(*) FROM audit_log) AS 审计数;

SELECT '档案与账号' AS 分类,
       (SELECT count(*) FROM supplier) AS 供应商,
       (SELECT count(*) FROM customer) AS 客户,
       (SELECT count(*) FROM product) AS 商品,
       (SELECT count(*) FROM warehouse) AS 仓库,
       (SELECT count(*) FROM sys_user) AS 用户;
