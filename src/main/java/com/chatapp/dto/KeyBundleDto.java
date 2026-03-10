package com.chatapp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyBundleDto {

    private Long userId;
    private String identityPublicKey;
    private String signedPreKey;
    private String signedPreKeySignature;
    private String oneTimePreKeys;
    private Integer keyVersion;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadRequest {
        @NotBlank(message = "身份公钥不能为空")
        private String identityPublicKey;

        @NotBlank(message = "签名预密钥不能为空")
        private String signedPreKey;

        @NotBlank(message = "签名不能为空")
        private String signedPreKeySignature;

        private String oneTimePreKeys;
    }
}
