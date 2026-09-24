package com.chatapp.dto;

import com.chatapp.entity.User;

import java.util.HashMap;
import java.util.Map;

/**
 * 把"别人"的资料摆给查看者时的唯一出口（好友、好友请求、成员列表、用户搜索……）。
 * 这里只放公开字段：邮箱、手机号只属于本人，永远不在这里出现；
 * 本人自己的资料走 {@link SelfUserDto} 或 UserProfileController 的 selfView。
 * 返回新建的可变 Map，调用方可以再按隐私设置抹掉在线状态、补头像框等。
 */
public final class PublicUserProfile {

    private PublicUserProfile() {
    }

    public static Map<String, Object> of(User user) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", user.getId());
        summary.put("username", user.getUsername());
        summary.put("displayName", user.getDisplayName());
        summary.put("avatarUrl", user.getAvatarUrl());
        summary.put("bio", user.getBio());
        summary.put("title", user.getTitle());
        summary.put("titleColor", user.getTitleColor());
        summary.put("titleEffect", user.getTitleEffect());
        summary.put("onlineStatus", user.getOnlineStatus());
        summary.put("lastSeen", user.getLastSeen());
        summary.put("isActive", user.getIsActive());
        summary.put("createdAt", user.getCreatedAt());
        summary.put("updatedAt", user.getUpdatedAt());
        return summary;
    }
}
