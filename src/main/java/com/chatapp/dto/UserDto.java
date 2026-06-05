package com.chatapp.dto;

import com.chatapp.entity.User;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    private Long id;
    private String username;
    private String email;
    private String phone;
    private String displayName;
    private String avatarUrl;
    private String avatarFramePreset;
    private String bio;
    private String title;
    private String titleColor;
    private String titleEffect;
    private User.OnlineStatus onlineStatus;
    private LocalDateTime lastSeen;
    private Boolean isActive;
    private LocalDateTime createdAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString(exclude = {"password", "clientHash"})
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;

        private String password;
        private String clientHash;

        public LoginRequest(String username, String password) {
            this.username = username;
            this.password = password;
        }

        @AssertTrue(message = "exactly one of {password, clientHash} must be provided")
        public boolean isExactlyOneCredential() {
            boolean hasPassword = password != null && !password.isBlank();
            boolean hasClientHash = clientHash != null && !clientHash.isBlank();
            return hasPassword ^ hasClientHash;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString(exclude = {"password", "clientHash", "clientSalt"})
    public static class RegisterRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 50, message = "用户名长度必须在3-50之间")
        private String username;

        @Size(min = 6, max = 100, message = "密码长度必须在6-100之间")
        private String password;

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        private String email;

        private String phone;
        private String displayName;
        private String clientHash;
        private String clientSalt;
        private String argon2Params;

        public RegisterRequest(String username, String password, String email, String phone, String displayName) {
            this.username = username;
            this.password = password;
            this.email = email;
            this.phone = phone;
            this.displayName = displayName;
        }

        @AssertTrue(message = "must provide either password OR (clientHash+clientSalt+argon2Params)")
        public boolean isValidCredentialBundle() {
            boolean hasPassword = password != null && !password.isBlank();
            boolean hasClientHash = clientHash != null && !clientHash.isBlank();
            boolean hasClientSalt = clientSalt != null && !clientSalt.isBlank();
            boolean hasArgon2Params = argon2Params != null && !argon2Params.isBlank();
            boolean oldPath = hasPassword && !hasClientHash && !hasClientSalt && !hasArgon2Params;
            boolean newPath = !hasPassword && hasClientHash && hasClientSalt && hasArgon2Params;
            return oldPath ^ newPath;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateUserRequest {
        @Size(max = 100, message = "显示名称最大100个字符")
        private String displayName;
        @Size(max = 500, message = "个人简介最大500个字符")
        private String bio;
        private String phone;
        private User.OnlineStatus onlineStatus;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString(exclude = {"oldPassword", "oldClientHash", "newPassword", "newClientHash", "newClientSalt"})
    public static class ChangePasswordRequest {
        private String oldPassword;
        private String oldClientHash;
        @Size(min = 6, max = 100, message = "密码长度必须在6-100之间")
        private String newPassword;
        private String newClientHash;
        private String newClientSalt;
        private String newArgon2Params;

        @AssertTrue(message = "must provide one old credential and one new credential bundle")
        public boolean isValidChange() {
            boolean hasOldPassword = oldPassword != null && !oldPassword.isBlank();
            boolean hasOldClientHash = oldClientHash != null && !oldClientHash.isBlank();
            boolean hasNewPassword = newPassword != null && !newPassword.isBlank();
            boolean hasNewClientHash = newClientHash != null && !newClientHash.isBlank();
            boolean hasNewClientSalt = newClientSalt != null && !newClientSalt.isBlank();
            boolean hasNewArgon2Params = newArgon2Params != null && !newArgon2Params.isBlank();
            boolean oldCredentialOk = hasOldPassword ^ hasOldClientHash;
            boolean newLegacyOk = hasNewPassword && !hasNewClientHash && !hasNewClientSalt && !hasNewArgon2Params;
            boolean newClientOk = !hasNewPassword && hasNewClientHash && hasNewClientSalt && hasNewArgon2Params;
            return oldCredentialOk && (newLegacyOk ^ newClientOk);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClientSaltParamsResponse {
        private String salt;
        private String argon2Params;
        private String scheme;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JwtResponse {
        private String accessToken;
        private String refreshToken;
        private String type = "Bearer";
        private UserDto user;

        public JwtResponse(String accessToken, String refreshToken, UserDto user) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.user = user;
        }

        // Legacy compatibility
        public JwtResponse(String token, UserDto user) {
            this.accessToken = token;
            this.user = user;
        }

        public String getToken() {
            return accessToken;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TitleRequest {
        @Size(max = 50, message = "头衔最大50个字符")
        private String title;
        private String titleColor;
        private String titleEffect;
    }
}
