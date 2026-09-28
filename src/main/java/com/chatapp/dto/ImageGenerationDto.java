package com.chatapp.dto;

import com.chatapp.entity.Message;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class ImageGenerationDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerateRequest {
        @NotNull
        private Long roomId;

        @NotBlank
        private String prompt;

        @Min(1)
        @Max(1)
        private Integer n = 1;

        private String size = "1024*1024";

        private Boolean expand = true;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerateResponse {
        private Long messageId;
        private Integer pointsCharged;
        private Message.ImageGenerationStatus status;
        private MessageDto message;
    }
}
