-- V4：采购域 + 销售域单据表（M2 核心交易）
-- 状态机：purchase_order DRAFT/SUBMITTED/PARTIAL_RECEIVED/COMPLETED
--         sales_order   DRAFT/SUBMITTED/PARTIAL_DELIVERED/COMPLETED
--         receive/delivery ACTIVE/VOID（VOID 作废红冲，M3 实现动作）

-- ============ 采购域 ============

CREATE TABLE purchase_order (
    id           BIGSERIAL PRIMARY KEY,
    order_no     VARCHAR(50) NOT NULL UNIQUE,
    supplier_id  BIGINT NOT NULL REFERENCES supplier (id),
    order_date   DATE NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    total_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_by   VARCHAR(50),
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE purchase_order_item (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT NOT NULL REFERENCES purchase_order (id),
    product_id BIGINT NOT NULL REFERENCES product (id),
    quantity   NUMERIC(14, 3) NOT NULL,
    unit_price NUMERIC(14, 2) NOT NULL,
    amount     NUMERIC(14, 2) NOT NULL
);

CREATE TABLE purchase_receive (
    id           BIGSERIAL PRIMARY KEY,
    receive_no   VARCHAR(50) NOT NULL UNIQUE,
    order_id     BIGINT NOT NULL REFERENCES purchase_order (id),
    receive_date DATE NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by   VARCHAR(50),
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

-- 追溯起点：批号 + 生产日期 + 效期 + 检验报告单号
CREATE TABLE purchase_receive_item (
    id            BIGSERIAL PRIMARY KEY,
    receive_id    BIGINT NOT NULL REFERENCES purchase_receive (id),
    product_id    BIGINT NOT NULL REFERENCES product (id),
    quantity      NUMERIC(14, 3) NOT NULL,
    batch_no      VARCHAR(50) NOT NULL,
    produce_date  DATE,
    expire_date   DATE NOT NULL,
    unit_cost     NUMERIC(14, 2) NOT NULL,
    inspection_no VARCHAR(100)
);

-- ============ 销售域 ============

CREATE TABLE sales_order (
    id           BIGSERIAL PRIMARY KEY,
    order_no     VARCHAR(50) NOT NULL UNIQUE,
    customer_id  BIGINT NOT NULL REFERENCES customer (id),
    order_date   DATE NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    total_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_by   VARCHAR(50),
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE sales_order_item (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT NOT NULL REFERENCES sales_order (id),
    product_id BIGINT NOT NULL REFERENCES product (id),
    quantity   NUMERIC(14, 3) NOT NULL,
    unit_price NUMERIC(14, 2) NOT NULL,
    amount     NUMERIC(14, 2) NOT NULL
);

CREATE TABLE sales_delivery (
    id            BIGSERIAL PRIMARY KEY,
    delivery_no   VARCHAR(50) NOT NULL UNIQUE,
    order_id      BIGINT NOT NULL REFERENCES sales_order (id),
    delivery_date DATE NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by    VARCHAR(50),
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

-- 锁批号：出库按 FEFO 拆分为多行，unit_cost 为结转成本（移动加权）
CREATE TABLE sales_delivery_item (
    id          BIGSERIAL PRIMARY KEY,
    delivery_id BIGINT NOT NULL REFERENCES sales_delivery (id),
    product_id  BIGINT NOT NULL REFERENCES product (id),
    batch_no    VARCHAR(50) NOT NULL,
    quantity    NUMERIC(14, 3) NOT NULL,
    unit_cost   NUMERIC(14, 2) NOT NULL
);

-- 索引
CREATE INDEX idx_purchase_order_supplier ON purchase_order (supplier_id);
CREATE INDEX idx_purchase_order_status ON purchase_order (status);
CREATE INDEX idx_purchase_order_item_order ON purchase_order_item (order_id);
CREATE INDEX idx_purchase_receive_order ON purchase_receive (order_id);
CREATE INDEX idx_purchase_receive_item_receive ON purchase_receive_item (receive_id);

CREATE INDEX idx_sales_order_customer ON sales_order (customer_id);
CREATE INDEX idx_sales_order_status ON sales_order (status);
CREATE INDEX idx_sales_order_item_order ON sales_order_item (order_id);
CREATE INDEX idx_sales_delivery_order ON sales_delivery (order_id);
CREATE INDEX idx_sales_delivery_item_delivery ON sales_delivery_item (delivery_id);
