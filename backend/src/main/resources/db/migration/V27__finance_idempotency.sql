-- V27：财务核销幂等唯一约束（批次十五 P0-3）
-- 1) 应收/应付按业务单据唯一：同一业务单据最多一条非 MANUAL 应收/应付，防止并发重复生成；
-- 2) 收款/付款核销明细按（单据, 发票）唯一：同一收款/付款单内同一发票只允许一行，防止逐行重复核销。
-- 只做加法，不改 V1~V26；全部 IF NOT EXISTS，可重复执行。
-- 说明：biz_type = 'MANUAL' 为手工补录（biz_id 固定 0），不受业务单据唯一约束限制。

-- 用途：应收单据按业务单据幂等；归属：ArApService.generateAr / V27 幂等兜底
-- 条件：biz_type <> 'MANUAL' 才建唯一约束
CREATE UNIQUE INDEX IF NOT EXISTS uk_ar_invoice_biz
    ON ar_invoice (biz_type, biz_id)
    WHERE biz_type <> 'MANUAL';

-- 用途：应付单据按业务单据幂等；归属：ArApService.generateAp / V27 幂等兜底
-- 条件：biz_type <> 'MANUAL' 才建唯一约束
CREATE UNIQUE INDEX IF NOT EXISTS uk_ap_invoice_biz
    ON ap_invoice (biz_type, biz_id)
    WHERE biz_type <> 'MANUAL';

-- 用途：同一收款单内同一应收单只允许核销一行；归属：ReceiptService.create（业务层先 400，索引兜底并发）
CREATE UNIQUE INDEX IF NOT EXISTS uk_receipt_item_receipt_ar
    ON receipt_item (receipt_id, ar_invoice_id);

-- 用途：同一付款单内同一应付单只允许核销一行；归属：PaymentService.create（业务层先 400，索引兜底并发）
CREATE UNIQUE INDEX IF NOT EXISTS uk_payment_item_payment_ap
    ON payment_item (payment_id, ap_invoice_id);
