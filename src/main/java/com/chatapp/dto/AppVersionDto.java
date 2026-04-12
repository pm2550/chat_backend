package com.chatapp.dto;

import com.chatapp.entity.DeviceToken;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppVersionDto {

    private Long id;
    private String platform;
    private String versionName;
    private Integer versionCode;
    private Boolean forceUpdate;
    private String releaseNotes;
    private String downloadUrl;
    private Long fileSize;
    private LocalDateTime createdAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckResponse {
        private boolean updateAvailable;
        private boolean forceUpdate;
        private String latestVersion;
        private Integer latestVersionCode;
        private String releaseNotes;
        private String downloadUrl;
        private Long fileSize;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublishRequest {
        @NotNull(message = "平台不能为空")
        private DeviceToken.Platform platform;

        @NotBlank(message = "版本名不能为空")
        private String versionName;

        @NotNull(message = "版本号不能为空")
        private Integer versionCode;

        private Boolean forceUpdate = false;
        private String releaseNotes;
    }
}
