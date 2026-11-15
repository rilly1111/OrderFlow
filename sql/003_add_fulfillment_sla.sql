-- IndusFlow 履约时效增量迁移，适用于已执行 001 和 002 的 MySQL 8.0.16+ 数据库。
-- 审核通过后，在确认的目标 schema 中手动执行一次。历史记录的新字段保留 NULL。

ALTER TABLE order_status_log
    DROP CHECK chk_order_status_log_from_status,
    DROP CHECK chk_order_status_log_to_status,
    ADD CONSTRAINT chk_order_status_log_fulfillment_from_status CHECK (
        from_status IS NULL
        OR CAST(from_status AS BINARY) IN ('PENDING_PAYMENT', 'PAID', 'IN_PROGRESS', 'OVERDUE', 'COMPLETED', 'CANCELLED')
    ),
    ADD CONSTRAINT chk_order_status_log_fulfillment_to_status CHECK (
        CAST(to_status AS BINARY) IN ('PENDING_PAYMENT', 'PAID', 'IN_PROGRESS', 'OVERDUE', 'COMPLETED', 'CANCELLED')
    );

ALTER TABLE orders
    ADD COLUMN equipment_code VARCHAR(64) DEFAULT NULL,
    ADD COLUMN fault_description VARCHAR(500) DEFAULT NULL,
    ADD COLUMN acceptance_deadline_at DATETIME(3) DEFAULT NULL,
    ADD COLUMN started_at DATETIME(3) DEFAULT NULL,
    ADD COLUMN overdue_at DATETIME(3) DEFAULT NULL,
    ADD INDEX idx_orders_status_acceptance_deadline (status, acceptance_deadline_at),
    DROP CHECK chk_orders_status,
    ADD CONSTRAINT chk_orders_fulfillment_status CHECK (
        CAST(status AS BINARY) IN ('PENDING_PAYMENT', 'PAID', 'IN_PROGRESS', 'OVERDUE', 'COMPLETED', 'CANCELLED')
    );
