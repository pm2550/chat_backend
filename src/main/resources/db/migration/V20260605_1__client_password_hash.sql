-- Client-side password hashing migration.
ALTER TABLE users
    ADD COLUMN client_salt VARCHAR(128) NULL AFTER password,
    ADD COLUMN argon2_params VARCHAR(64) NULL AFTER client_salt,
    ADD COLUMN password_scheme VARCHAR(32) NOT NULL DEFAULT 'BCRYPT_LEGACY' AFTER argon2_params;
