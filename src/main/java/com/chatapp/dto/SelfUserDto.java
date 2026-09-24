package com.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 本人看自己的资料：公开资料 + 邮箱、手机号。JSON 是平铺的（和以前的 UserDto 同形），
 * 只能用当前登录用户自己的数据构造，绝不能拿来装别人。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SelfUserDto {

    @JsonUnwrapped
    private UserDto profile;
    private String email;
    private String phone;

    @JsonIgnore
    public Long getId() {
        return profile == null ? null : profile.getId();
    }

    @JsonIgnore
    public String getUsername() {
        return profile == null ? null : profile.getUsername();
    }
}
