package com.chatapp.dto;

import com.chatapp.entity.Sticker;
import com.chatapp.entity.StickerPack;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StickerDto {
    private Long id;
    private Long packId;
    private String url;
    private String keyword;
    private Integer indexInPack;

    public static StickerDto fromEntity(Sticker sticker) {
        if (sticker == null) return null;
        return new StickerDto(
                sticker.getId(),
                sticker.getPack() == null ? null : sticker.getPack().getId(),
                sticker.getUrl(),
                sticker.getKeyword(),
                sticker.getIndexInPack());
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackInfo {
        private Long id;
        private String name;
        private Long ownerUserId;
        private Boolean isPublic;
        private String coverUrl;

        public static PackInfo fromEntity(StickerPack pack) {
            if (pack == null) return null;
            return new PackInfo(
                    pack.getId(),
                    pack.getName(),
                    pack.getOwnerUser() == null ? null : pack.getOwnerUser().getId(),
                    Boolean.TRUE.equals(pack.getIsPublic()),
                    pack.getCoverUrl());
        }
    }
}
