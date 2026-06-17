-- Ordinary users should have a small daily free quota for AI image generation.
-- Paid points are only consumed after this per-user, per-day quota is exhausted.
INSERT INTO feature_costs (feature_key, cost_points, free_daily_quota, description)
VALUES ('image_generation', 10, 3, 'AI image generation in chat')
ON DUPLICATE KEY UPDATE
    cost_points = VALUES(cost_points),
    free_daily_quota = 3,
    description = VALUES(description);
