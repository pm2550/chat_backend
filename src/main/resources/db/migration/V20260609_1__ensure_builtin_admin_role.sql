-- The built-in admin account was originally created with only USER role in
-- production. Keep the fix narrow: only username='admin' receives ADMIN.
INSERT INTO user_roles (user_id, role)
SELECT u.id, 'ADMIN'
FROM users u
WHERE u.username = 'admin'
  AND NOT EXISTS (
      SELECT 1
      FROM user_roles ur
      WHERE ur.user_id = u.id
        AND ur.role = 'ADMIN'
  );
