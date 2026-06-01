UPDATE sticker_packs
SET cover_url = '/sticker-packs/system-default/happy.svg'
WHERE name = 'PM 默认贴纸' AND owner_user_id IS NULL;

UPDATE stickers
SET url = '/sticker-packs/system-default/happy.svg', keyword = 'happy'
WHERE pack_id IN (SELECT id FROM sticker_packs WHERE name = 'PM 默认贴纸' AND owner_user_id IS NULL)
  AND index_in_pack = 0;

UPDATE stickers
SET url = '/sticker-packs/system-default/sad.svg', keyword = 'sad'
WHERE pack_id IN (SELECT id FROM sticker_packs WHERE name = 'PM 默认贴纸' AND owner_user_id IS NULL)
  AND index_in_pack = 1;

UPDATE stickers
SET url = '/sticker-packs/system-default/wow.svg', keyword = 'wow'
WHERE pack_id IN (SELECT id FROM sticker_packs WHERE name = 'PM 默认贴纸' AND owner_user_id IS NULL)
  AND index_in_pack = 2;

UPDATE stickers
SET url = '/sticker-packs/system-default/angry.svg', keyword = 'angry'
WHERE pack_id IN (SELECT id FROM sticker_packs WHERE name = 'PM 默认贴纸' AND owner_user_id IS NULL)
  AND index_in_pack = 3;

UPDATE stickers
SET url = '/sticker-packs/system-default/think.svg', keyword = 'think'
WHERE pack_id IN (SELECT id FROM sticker_packs WHERE name = 'PM 默认贴纸' AND owner_user_id IS NULL)
  AND index_in_pack = 4;

UPDATE stickers
SET url = '/sticker-packs/system-default/laugh.svg', keyword = 'laugh'
WHERE pack_id IN (SELECT id FROM sticker_packs WHERE name = 'PM 默认贴纸' AND owner_user_id IS NULL)
  AND index_in_pack = 5;
