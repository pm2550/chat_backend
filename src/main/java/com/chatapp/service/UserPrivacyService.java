package com.chatapp.service;

import com.chatapp.dto.MessageDto;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.entity.UserSettings;
import com.chatapp.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 把用户在"设置"里的开关落到实处的唯一入口：谁能收到某人的在线状态、已读回执、
 * 离线推送，谁能加他好友、跟他发起私聊，都在这里判定。
 *
 * <p>没有 user_settings 行的用户按默认值（全部开启）处理。方法都按"被关掉"来命名，
 * 这样一个未配置的 mock 返回 false 时恰好就是默认行为。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserPrivacyService {

    private final UserSettingsRepository userSettingsRepository;

    /** 关闭"消息通知"：不收任何消息的离线推送 / 后台连接通知（来电不受影响）。 */
    public boolean messageNotificationsDisabled(Long userId) {
        return isOff(userId, UserSettings::getMessageNotificationsEnabled);
    }

    public Set<Long> usersWithMessageNotificationsDisabled(Collection<Long> userIds) {
        return idsWhereOff(userIds, userSettingsRepository::findUserIdsWithMessageNotificationsDisabled);
    }

    /** 关闭"已读回执"：别人看不到他读了什么；按惯例他也看不到别人的已读。 */
    public boolean readReceiptsDisabled(Long userId) {
        return isOff(userId, UserSettings::getReadReceiptsEnabled);
    }

    public Set<Long> usersWithReadReceiptsDisabled(Collection<Long> userIds) {
        return idsWhereOff(userIds, userSettingsRepository::findUserIdsWithReadReceiptsDisabled);
    }

    /** 关闭"显示在线状态"：除本人外，别人只能看到他离线、看不到最后在线时间。 */
    public boolean hidesOnlineStatus(Long userId) {
        return isOff(userId, UserSettings::getShowOnlineStatus);
    }

    public Set<Long> usersHidingOnlineStatus(Collection<Long> userIds) {
        return idsWhereOff(userIds, userSettingsRepository::findUserIdsHidingOnlineStatus);
    }

    /** viewer 看 subject 时，subject 的在线状态是否要隐藏。本人永远看得到自己。 */
    public boolean hidesOnlineStatusFrom(Long subjectId, Long viewerId) {
        return subjectId != null && !subjectId.equals(viewerId) && hidesOnlineStatus(subjectId);
    }

    public boolean rejectsFriendRequests(Long userId) {
        return isOff(userId, UserSettings::getAllowFriendRequests);
    }

    /** 关闭"允许私聊"：非好友不能新建与他的私聊（已有会话照常）。 */
    public boolean rejectsDirectMessagesFromStrangers(Long userId) {
        return isOff(userId, UserSettings::getAllowDirectMessages);
    }

    /**
     * 把对外展示的用户摘要里的在线字段按隐私设置抹掉：状态一律显示离线，最后在线时间置空。
     * 摘要必须是新建的 Map（不要传实体），否则会把改动写回数据库。
     */
    public void maskPresence(Map<String, Object> userSummary, Long subjectId, Long viewerId) {
        if (hidesOnlineStatusFrom(subjectId, viewerId)) {
            userSummary.put("onlineStatus", User.OnlineStatus.OFFLINE);
            userSummary.put("lastSeen", null);
        }
    }

    /**
     * 关了已读回执的人也看不到别人的已读（与微信/WhatsApp 一致）：
     * 他发出去的消息在他这边不显示"已读"和已读人数。
     */
    public List<MessageDto> maskReadStateForViewer(List<MessageDto> messages, Long viewerId) {
        if (messages == null || messages.isEmpty() || viewerId == null || !readReceiptsDisabled(viewerId)) {
            return messages;
        }
        for (MessageDto message : messages) {
            if (message == null || !viewerId.equals(message.getSenderId())) {
                continue;
            }
            if (message.getMessageStatus() == Message.MessageStatus.READ) {
                message.setMessageStatus(Message.MessageStatus.DELIVERED);
            }
            message.setReadCount(0);
        }
        return messages;
    }

    private boolean isOff(Long userId, Function<UserSettings, Boolean> flag) {
        if (userId == null) {
            return false;
        }
        return userSettingsRepository.findByUserId(userId)
                .map(flag)
                .map(Boolean.FALSE::equals)
                .orElse(false);
    }

    private Set<Long> idsWhereOff(Collection<Long> userIds,
                                  Function<Collection<Long>, List<Long>> query) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        List<Long> distinct = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(query.apply(distinct));
    }

    /** "显示在线状态"被切换时发布，让在线连接立即广播对应的上线/离线。 */
    public record PresenceVisibilityChanged(Long userId, boolean visible) {
    }
}
