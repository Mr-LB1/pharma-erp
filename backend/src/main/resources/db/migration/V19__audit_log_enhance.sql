-- V19：审计增强（P1-2）
-- 审计行增加来源 IP 与关键操作前后值快照（JSON 文本），幂等可重跑。
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS ip VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS before_value TEXT;
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS after_value TEXT;
