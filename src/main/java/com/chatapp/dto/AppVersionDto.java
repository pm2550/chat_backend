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
    /** Android 分架构包的 ABI；整包（含所有非 Android 平台）为 null。 */
    private String abi;
    private String versionName;
    private Integer versionCode;
    private Boolean forceUpdate;
    private String releaseNotes;
    private String downloadUrl;
    private Long fileSize;
    private String sha256;
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
        private String sha256;
        /** 这个下载地址对应的 Android ABI；整包为 null。 */
        private String abi;
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

        /** Android 分架构包：arm64-v8a / armeabi-v7a；不填 = 整包（其他平台必须不填）。 */
        private String abi;

        public PublishRequest(DeviceToken.Platform platform, String versionName, Integer versionCode,
                              Boolean forceUpdate, String releaseNotes) {
            this(platform, versionName, versionCode, forceUpdate, releaseNotes, null);
        }
    }
}
