package com.chatapp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户资料更新请求DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileUpdateRequest {

    @Size(max = 100, message = "显示名称长度不能超过100字符")
    private String displayName;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过100字符")
    private String email;

    @Size(max = 20, message = "手机号长度不能超过20字符")
    private String phone;

    @Size(max = 500, message = "个人简介长度不能超过500字符")
    private String bio;

    /**
     * 在线状态
     */
    private String onlineStatus;
} 