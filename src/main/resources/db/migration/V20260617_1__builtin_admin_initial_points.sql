-- Give the built-in admin account enough paid points to operate AI features
-- and generate redemption codes immediately after the points system is enabled.
-- The non-null ref makes this grant idempotent across restored databases.
INSERT INTO user_balance (user_id, paid_points, updated_at)
SELECT u.id, 10000, NOW()
FROM users u
WHERE u.username = 'admin'
  AND NOT EXISTS (
      SELECT 1
      FROM points_ledger pl
      WHERE pl.user_id = u.id
        AND pl.reason = 'ADMIN_CREDIT'
        AND pl.ref_key = 'admin_bootstrap'
        AND pl.ref_id = 'builtin-admin-initial-points-v1'
  )
ON DUPLICATE KEY UPDATE
    paid_points = paid_points + VALUES(paid_points),
    updated_at = NOW();

INSERT INTO points_ledger (
    user_id,
    delta,
    reason,
    ref_key,
    ref_id,
    balance_paid_after,
    free_used,
    memo,
    created_at
)
SELECT
    u.id,
    10000,
    'ADMIN_CREDIT',
    'admin_bootstrap',
    'builtin-admin-initial-points-v1',
    ub.paid_points,
    0,
    '内置管理员初始积分',
    NOW()
FROM users u
JOIN user_balance ub ON ub.user_id = u.id
WHERE u.username = 'admin'
  AND NOT EXISTS (
      SELECT 1
      FROM points_ledger pl
      WHERE pl.user_id = u.id
        AND pl.reason = 'ADMIN_CREDIT'
        AND pl.ref_key = 'admin_bootstrap'
        AND pl.ref_id = 'builtin-admin-initial-points-v1'
  );
