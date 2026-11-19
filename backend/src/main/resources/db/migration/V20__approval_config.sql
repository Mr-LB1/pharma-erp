-- V20：金额分级审批（P1-1）
-- 按单据金额区间解析审批层级与所需审批角色；无命中配置时服务端回落 level 1 / APPROVER。
-- 幂等：表与唯一约束 IF NOT EXISTS，种子 ON CONFLICT DO NOTHING。
CREATE TABLE IF NOT EXISTS approval_config (
    id            BIGSERIAL PRIMARY KEY,
    biz_type      VARCHAR(30)    NOT NULL,
    min_amount    NUMERIC(18, 2) NOT NULL,
    max_amount    NUMERIC(18, 2) NOT NULL,
    level         INT            NOT NULL,
    approver_role VARCHAR(50)    NOT NULL,
    enabled       BOOLEAN        NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_approval_config_range UNIQUE (biz_type, min_amount, max_amount)
);

-- 种子：订单 0~99999.99 为 L1；>=100000 为 L2（均为 APPROVER 角色，ADMIN 服务端豁免）
INSERT INTO approval_config (biz_type, min_amount, max_amount, level, approver_role, enabled) VALUES
    ('PURCHASE_ORDER', 0.00,        99999.99,      1, 'APPROVER', TRUE),
    ('PURCHASE_ORDER', 100000.00,   999999999.99,  2, 'APPROVER', TRUE),
    ('SALES_ORDER',    0.00,        99999.99,      1, 'APPROVER', TRUE),
    ('SALES_ORDER',    100000.00,   999999999.99,  2, 'APPROVER', TRUE)
ON CONFLICT DO NOTHING;
