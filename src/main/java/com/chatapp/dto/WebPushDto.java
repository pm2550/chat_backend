package com.chatapp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class WebPushDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VapidPublicKeyResponse {
        private String publicKey;
        private boolean configured;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubscribeRequest {
        @NotBlank
        private String endpoint;

        @Valid
        @NotNull
        private Keys keys;

        private String userAgent;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnsubscribeRequest {
        @NotBlank
        private String endpoint;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Keys {
        @NotBlank
        private String p256dh;

        @NotBlank
        private String auth;
    }
}
