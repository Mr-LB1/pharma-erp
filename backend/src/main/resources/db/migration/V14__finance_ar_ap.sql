-- V14：财务 AR/AP（应收应付）+ 收付款核销 + 发票登记
-- 只做加法，不改 V1~V13；全部语句幂等可重跑。
-- 1) ar_invoice：应收单据（销售出库自动生成，作废联动 VOID）
-- 2) ap_invoice：应付单据（采购入库自动生成，作废联动 VOID）
-- 3) receipt / receipt_item：收款单 + 核销明细（按应收单核销）
-- 4) payment / payment_item：付款单 + 核销明细（按应付单核销）
-- 5) invoice_register：发票登记（收票/开票留痕）

-- ============ 1. 应收单据 ============
CREATE TABLE IF NOT EXISTS ar_invoice (
    id             BIGSERIAL PRIMARY KEY,
    invoice_no     VARCHAR(50) NOT NULL UNIQUE,
    customer_id    BIGINT NOT NULL,
    biz_type       VARCHAR(30) NOT NULL,          -- SALES_DELIVERY
    biz_id         BIGINT NOT NULL,               -- 出库单 id
    invoice_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    settled_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    status         VARCHAR(20) NOT NULL DEFAULT 'UNSETTLED',  -- UNSETTLED/PARTIAL/SETTLED/VOID
    remark         VARCHAR(500),
    created_by     VARCHAR(50),
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ar_invoice_customer ON ar_invoice (customer_id);
CREATE INDEX IF NOT EXISTS idx_ar_invoice_biz ON ar_invoice (biz_type, biz_id);

-- ============ 2. 应付单据 ============
CREATE TABLE IF NOT EXISTS ap_invoice (
    id             BIGSERIAL PRIMARY KEY,
    invoice_no     VARCHAR(50) NOT NULL UNIQUE,
    supplier_id    BIGINT NOT NULL,
    biz_type       VARCHAR(30) NOT NULL,          -- PURCHASE_RECEIVE
    biz_id         BIGINT NOT NULL,               -- 入库单 id
    invoice_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    settled_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    status         VARCHAR(20) NOT NULL DEFAULT 'UNSETTLED',
    remark         VARCHAR(500),
    created_by     VARCHAR(50),
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ap_invoice_supplier ON ap_invoice (supplier_id);
CREATE INDEX IF NOT EXISTS idx_ap_invoice_biz ON ap_invoice (biz_type, biz_id);

-- ============ 3. 收款单 ============
CREATE TABLE IF NOT EXISTS receipt (
    id          BIGSERIAL PRIMARY KEY,
    receipt_no  VARCHAR(50) NOT NULL UNIQUE,
    customer_id BIGINT NOT NULL,
    amount      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/VOID
    remark      VARCHAR(500),
    created_by  VARCHAR(50),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS receipt_item (
    id            BIGSERIAL PRIMARY KEY,
    receipt_id    BIGINT NOT NULL,
    ar_invoice_id BIGINT NOT NULL,
    amount        NUMERIC(18, 2) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_receipt_item_receipt ON receipt_item (receipt_id);

-- ============ 4. 付款单 ============
CREATE TABLE IF NOT EXISTS payment (
    id          BIGSERIAL PRIMARY KEY,
    payment_no  VARCHAR(50) NOT NULL UNIQUE,
    supplier_id BIGINT NOT NULL,
    amount      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    remark      VARCHAR(500),
    created_by  VARCHAR(50),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS payment_item (
    id            BIGSERIAL PRIMARY KEY,
    payment_id    BIGINT NOT NULL,
    ap_invoice_id BIGINT NOT NULL,
    amount        NUMERIC(18, 2) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_payment_item_payment ON payment_item (payment_id);

-- ============ 5. 发票登记 ============
CREATE TABLE IF NOT EXISTS invoice_register (
    id         BIGSERIAL PRIMARY KEY,
    invoice_no VARCHAR(100) NOT NULL,
    biz_type   VARCHAR(30),                       -- 关联单据类型（可空）
    biz_id     BIGINT,
    amount     NUMERIC(18, 2) NOT NULL DEFAULT 0,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    remark     VARCHAR(500),
    created_by VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
