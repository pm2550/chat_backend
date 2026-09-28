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

        /** 旧客户端的开关：false = 不扩写。新客户端改传 {@link #promptHelper}。 */
        private Boolean expand = true;

        /**
         * 画图扩写档位："off"（按原文）/ "low"（只翻译不改写）/ "medium"（创意扩写）。
         * 不传时按 expand 推断：expand=false → off，否则 medium。
         */
        private String promptHelper;

        public GenerateRequest(Long roomId, String prompt, Integer n, String size, Boolean expand) {
            this(roomId, prompt, n, size, expand, null);
        }
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
