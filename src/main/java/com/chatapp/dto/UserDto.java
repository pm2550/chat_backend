package com.chatapp.dto;

import com.chatapp.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户DTO类
 */
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
    private String bio;
    private User.OnlineStatus onlineStatus;
    private LocalDateTime lastSeen;
    private Boolean isActive;
    private LocalDateTime createdAt;

    /**
     * 登录请求DTO
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        private String username;
        private String password;
    }

    /**
     * 注册请求DTO
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterRequest {
        private String username;
        private String password;
        private String email;
        private String phone;
        private String displayName;
    }

    /**
     * 更新用户信息请求DTO
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateUserRequest {
        private String displayName;
        private String bio;
        private String phone;
        private User.OnlineStatus onlineStatus;
    }

    /**
     * 修改密码请求DTO
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;
    }

    /**
     * JWT响应DTO
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JwtResponse {
        private String token;
        private String type = "Bearer";
        private UserDto user;

        public JwtResponse(String token, UserDto user) {
            this.token = token;
            this.user = user;
        }
    }
} 