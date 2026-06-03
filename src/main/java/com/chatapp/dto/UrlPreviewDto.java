package com.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UrlPreviewDto {
    private String url;
    private String title;
    private String description;
    private String imageUrl;
    private String siteName;
    private String faviconUrl;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private String url;
    }
}
