package com.chatapp.service;

import java.util.List;

/**
 * 会话资料 / 成员变化的领域事件。服务层在事务里发布，事务提交后由
 * {@code RoomRealtimeEventListener} 推送给在线用户——这样不管改动来自 REST、
 * 机器人网关还是 agent 工具，所有端都能实时看到，也不会推送一个最终回滚了的改动。
 */
public final class RoomRealtimeEvents {

    private RoomRealtimeEvents() {
    }

    /** 群名、公告、头像、背景、匿名开关/主题等房间级字段变了。 */
    public record RoomUpdated(Long chatRoomId) {
    }

    /** 这些用户成了房间成员（建群、邀请、主动加入）。 */
    public record MembersAdded(Long chatRoomId, List<Long> userIds) {
    }

    /** 这些用户不再是房间成员。 */
    public record MembersRemoved(Long chatRoomId, List<Long> userIds, Reason reason) {
    }

    public enum Reason {
        /** 自己退出 */
        LEFT,
        /** 被管理员或机器人移出 */
        KICKED,
        /** 群被解散 */
        DELETED;

        public String wireName() {
            return name().toLowerCase();
        }
    }
}
