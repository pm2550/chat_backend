INSERT INTO users (
    username,
    email,
    password,
    display_name,
    is_active,
    online_status,
    created_at,
    updated_at
)
SELECT
    'system',
    'system@pmchat.local',
    '$2a$10$7QJ8QwW7kJniE1M79gAfveDhH2mNWUOzkNijXmwa7bqoqnWYs83Je',
    'PM chat System',
    b'0',
    'OFFLINE',
    NOW(6),
    NOW(6)
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE username = 'system'
);
