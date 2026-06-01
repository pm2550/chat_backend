CREATE TABLE IF NOT EXISTS sticker_packs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    owner_user_id BIGINT NULL,
    is_public BOOLEAN NOT NULL DEFAULT TRUE,
    cover_url VARCHAR(1024) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sticker_packs_owner FOREIGN KEY (owner_user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS stickers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pack_id BIGINT NOT NULL,
    url VARCHAR(1024) NULL,
    keyword VARCHAR(80) NULL,
    index_in_pack INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_stickers_pack FOREIGN KEY (pack_id) REFERENCES sticker_packs(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sticker_pack_subscriptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pack_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_sticker_pack_subscription UNIQUE (pack_id, user_id),
    CONSTRAINT fk_sticker_subscription_pack FOREIGN KEY (pack_id) REFERENCES sticker_packs(id) ON DELETE CASCADE,
    CONSTRAINT fk_sticker_subscription_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

INSERT INTO sticker_packs (name, owner_user_id, is_public, cover_url)
SELECT 'PM 默认贴纸', NULL, TRUE, NULL
WHERE NOT EXISTS (SELECT 1 FROM sticker_packs WHERE name = 'PM 默认贴纸' AND owner_user_id IS NULL);

INSERT INTO stickers (pack_id, url, keyword, index_in_pack)
SELECT p.id, NULL, '😀', 0 FROM sticker_packs p
WHERE p.name = 'PM 默认贴纸' AND p.owner_user_id IS NULL
AND NOT EXISTS (SELECT 1 FROM stickers s WHERE s.pack_id = p.id AND s.keyword = '😀');

INSERT INTO stickers (pack_id, url, keyword, index_in_pack)
SELECT p.id, NULL, '👍', 1 FROM sticker_packs p
WHERE p.name = 'PM 默认贴纸' AND p.owner_user_id IS NULL
AND NOT EXISTS (SELECT 1 FROM stickers s WHERE s.pack_id = p.id AND s.keyword = '👍');

INSERT INTO stickers (pack_id, url, keyword, index_in_pack)
SELECT p.id, NULL, '🎉', 2 FROM sticker_packs p
WHERE p.name = 'PM 默认贴纸' AND p.owner_user_id IS NULL
AND NOT EXISTS (SELECT 1 FROM stickers s WHERE s.pack_id = p.id AND s.keyword = '🎉');

INSERT INTO stickers (pack_id, url, keyword, index_in_pack)
SELECT p.id, NULL, '🚀', 3 FROM sticker_packs p
WHERE p.name = 'PM 默认贴纸' AND p.owner_user_id IS NULL
AND NOT EXISTS (SELECT 1 FROM stickers s WHERE s.pack_id = p.id AND s.keyword = '🚀');

INSERT INTO stickers (pack_id, url, keyword, index_in_pack)
SELECT p.id, NULL, '❤️', 4 FROM sticker_packs p
WHERE p.name = 'PM 默认贴纸' AND p.owner_user_id IS NULL
AND NOT EXISTS (SELECT 1 FROM stickers s WHERE s.pack_id = p.id AND s.keyword = '❤️');

INSERT INTO stickers (pack_id, url, keyword, index_in_pack)
SELECT p.id, NULL, '✨', 5 FROM sticker_packs p
WHERE p.name = 'PM 默认贴纸' AND p.owner_user_id IS NULL
AND NOT EXISTS (SELECT 1 FROM stickers s WHERE s.pack_id = p.id AND s.keyword = '✨');
