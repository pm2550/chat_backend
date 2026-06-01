package com.chatapp.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnonymousDto {

    private Long id;
    private String anonymousName;
    private String anonymousAvatar;
    private Boolean customNameUsed;
    private ThemeInfo theme;
    private Integer dailyRemaining;
    private String quotaResetsAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RenameRequest {
        @Size(min = 2, max = 20, message = "匿名昵称长度2-20个字符")
        private String newName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnonymousMessageInfo {
        private String anonymousName;
        private String anonymousAvatar;
        private Boolean isAnonymous;
        private ThemeInfo theme;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ThemeInfo {
        private Long id;
        private String themeKey;
        private String displayName;
        private String description;
        private String accentColor;
        private String backgroundColor;
        private String messageColor;
        private String personaPrefix;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ThemeRequest {
        private String themeKey;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuotaInfo {
        private Integer used;
        private Integer remaining;
        private String resetsAt;
    }
}
