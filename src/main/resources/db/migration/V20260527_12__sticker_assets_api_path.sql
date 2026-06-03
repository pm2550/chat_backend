UPDATE sticker_packs
SET cover_url = REPLACE(cover_url, '/sticker-packs/', '/api/sticker-packs/')
WHERE cover_url LIKE '/sticker-packs/%';

UPDATE stickers
SET url = REPLACE(url, '/sticker-packs/', '/api/sticker-packs/')
WHERE url LIKE '/sticker-packs/%';
