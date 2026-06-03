package com.chatapp.dto;

import com.chatapp.entity.BotConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class ProviderCredentialDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private BotConfig.LLMProvider llmProvider;
        private String label;
        private String secretLast4;
        private Boolean isActive;
        private String memo;
        private String baseUrl;
        private String modelOverride;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotNull(message = "LLM提供者不能为空")
        private BotConfig.LLMProvider llmProvider;

        @NotBlank(message = "凭据名称不能为空")
        private String label;

        @NotBlank(message = "密钥不能为空")
        private String secret;

        private String memo;
        private String baseUrl;
        private String modelOverride;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String label;
        private String secret;
        private Boolean isActive;
        private String memo;
        private String baseUrl;
        private String modelOverride;
    }
}
