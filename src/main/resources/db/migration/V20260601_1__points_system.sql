CREATE TABLE user_balance (
    user_id BIGINT NOT NULL,
    paid_points INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_balance_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE daily_feature_usage (
    user_id BIGINT NOT NULL,
    feature_key VARCHAR(60) NOT NULL,
    usage_date DATE NOT NULL,
    count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, feature_key, usage_date),
    KEY idx_daily_usage_date (usage_date),
    CONSTRAINT fk_daily_feature_usage_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE points_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    delta INT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    ref_key VARCHAR(60),
    ref_id VARCHAR(120),
    balance_paid_after INT NOT NULL,
    free_used INT NOT NULL DEFAULT 0,
    free_remaining_after INT,
    memo TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ledger_user_created (user_id, created_at DESC),
    UNIQUE KEY uk_ledger_user_reason_ref (user_id, reason, ref_key, ref_id),
    CONSTRAINT fk_points_ledger_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE redemption_codes (
    code_hash VARCHAR(64) NOT NULL,
    points INT NOT NULL,
    batch_label VARCHAR(80),
    memo TEXT,
    issued_by_user_id BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6),
    redeemed_by_user_id BIGINT,
    redeemed_at DATETIME(6),
    PRIMARY KEY (code_hash),
    KEY idx_redemption_codes_redeemed_at (redeemed_at),
    KEY idx_redemption_codes_batch_label (batch_label),
    CONSTRAINT fk_redemption_codes_issued_by FOREIGN KEY (issued_by_user_id) REFERENCES users(id),
    CONSTRAINT fk_redemption_codes_redeemed_by FOREIGN KEY (redeemed_by_user_id) REFERENCES users(id),
    CONSTRAINT chk_redemption_codes_points_positive CHECK (points > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE feature_costs (
    feature_key VARCHAR(60) NOT NULL,
    cost_points INT NOT NULL,
    free_daily_quota INT NOT NULL DEFAULT 0,
    enabled BIT NOT NULL DEFAULT b'1',
    description TEXT,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (feature_key),
    CONSTRAINT chk_feature_costs_cost_nonnegative CHECK (cost_points >= 0),
    CONSTRAINT chk_feature_costs_free_quota_nonnegative CHECK (free_daily_quota >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO feature_costs (feature_key, cost_points, free_daily_quota, description)
VALUES ('test_debit', 1, 10, 'Placeholder for end-to-end testing; remove or disable in production');
