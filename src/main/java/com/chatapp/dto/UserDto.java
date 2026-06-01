package com.chatapp.dto;

import com.chatapp.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private User.OnlineStatus onlineStatus;
    private LocalDateTime lastSeen;
    private Boolean isActive;
    private LocalDateTime createdAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;

        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 50, message = "用户名长度必须在3-50之间")
        private String username;

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 100, message = "密码长度必须在6-100之间")
        private String password;

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        private String email;

        private String phone;
        private String displayName;
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
    public static class ChangePasswordRequest {
        @NotBlank(message = "旧密码不能为空")
        private String oldPassword;

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 100, message = "密码长度必须在6-100之间")
        private String newPassword;
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
}
