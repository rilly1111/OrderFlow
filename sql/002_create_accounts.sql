CREATE TABLE accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) COLLATE utf8mb4_0900_as_ci NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_accounts_username (username),
    CONSTRAINT chk_accounts_role CHECK (
        CAST(role AS BINARY) IN ('USER', 'ADMIN')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
