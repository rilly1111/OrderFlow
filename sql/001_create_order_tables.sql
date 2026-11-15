-- OrderFlow 第一版订单表结构。目标 MySQL 版本为 8.0.16 或更高的 8.0 版本。
-- 业务时间由应用按 Asia/Shanghai 写入 DATETIME(3)。

CREATE TABLE orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    pay_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(24) NOT NULL,
    payment_method VARCHAR(24) DEFAULT NULL,
    payment_transaction_no VARCHAR(64) DEFAULT NULL,
    paid_at DATETIME(3) DEFAULT NULL,
    version INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_no (order_no),
    UNIQUE KEY uk_orders_payment_transaction_no (payment_transaction_no),
    KEY idx_orders_user_created_at (user_id, created_at),
    KEY idx_orders_created_at_status (created_at, status),
    CONSTRAINT chk_orders_pay_amount CHECK (pay_amount >= 0),
    CONSTRAINT chk_orders_status CHECK (
        CAST(status AS BINARY) IN ('PENDING_PAYMENT', 'PAID', 'COMPLETED', 'CANCELLED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_status_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    from_status VARCHAR(24) DEFAULT NULL,
    to_status VARCHAR(24) NOT NULL,
    operator_type VARCHAR(16) NOT NULL,
    operator_id BIGINT DEFAULT NULL,
    remark VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_status_log_order_created_at (order_id, created_at),
    CONSTRAINT chk_order_status_log_from_status CHECK (
        from_status IS NULL
        OR CAST(from_status AS BINARY) IN ('PENDING_PAYMENT', 'PAID', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT chk_order_status_log_to_status CHECK (
        CAST(to_status AS BINARY) IN ('PENDING_PAYMENT', 'PAID', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT chk_order_status_log_operator_type CHECK (
        CAST(operator_type AS BINARY) IN ('USER', 'ADMIN', 'SYSTEM')
    ),
    CONSTRAINT chk_order_status_log_operator_id CHECK (
        operator_id IS NOT NULL OR CAST(operator_type AS BINARY) = 'SYSTEM'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
