package com.chatapp.service;

import com.chatapp.entity.ChatRoomBot;
import com.chatapp.repository.ChatRoomBotRepository;
import com.chatapp.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI-as-admin moderation (F5 Slice 2). A room OWNER grants a bound bot a moderation level
 * ({@link ChatRoomBot.ModerationGrant}); the bot's mute_member / kick_member agent-tools then
 * act through this service. A bot may NEVER moderate the owner or an admin, and its grant is
 * scoped per room — independent of whichever user triggered it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ModerationService {

    private final ChatRoomBotRepository chatRoomBotRepository;
    private final ChatRoomRepository chatRoomRepository;

    /** A room OWNER sets a bot's moderation grant in their room. */
    public void setBotModerationGrant(Long roomId, Long ownerId, Long botConfigId,
                                      ChatRoomBot.ModerationGrant grant) {
        if (!chatRoomRepository.isOwner(roomId, ownerId)) {
            throw new AccessDeniedException("只有群主可以授予机器人管理权限");
        }
        ChatRoomBot binding = chatRoomBotRepository.findByChatRoomIdAndBotConfigId(roomId, botConfigId)
                .orElseThrow(() -> new IllegalArgumentException("机器人未加入该聊天室"));
        binding.setModerationGrant(grant == null ? ChatRoomBot.ModerationGrant.NONE : grant);
        chatRoomBotRepository.save(binding);
        log.info("聊天室 {} 群主 {} 设置机器人 {} 管理权限为 {}", roomId, ownerId, botConfigId, binding.getModerationGrant());
    }

    /** Bot mutes/unmutes a member (requires MUTE grant). */
    public void muteByBot(Long botConfigId, Long roomId, Long targetUserId, boolean mute) {
        requireGrantedBot(botConfigId, roomId, ChatRoomBot.ModerationGrant.MUTE);
        requireModeratableTarget(roomId, targetUserId);
        chatRoomRepository.setMemberMuted(roomId, targetUserId, mute);
        log.info("机器人 {} 在聊天室 {} {}了成员 {}", botConfigId, roomId, mute ? "禁言" : "解除禁言", targetUserId);
    }

    /** Bot removes a member (requires KICK grant). */
    public void kickByBot(Long botConfigId, Long roomId, Long targetUserId) {
        requireGrantedBot(botConfigId, roomId, ChatRoomBot.ModerationGrant.KICK);
        requireModeratableTarget(roomId, targetUserId);
        chatRoomRepository.removeMember(roomId, targetUserId);
        log.info("机器人 {} 在聊天室 {} 移除了成员 {}", botConfigId, roomId, targetUserId);
    }

    // ---- helpers ----

    private ChatRoomBot requireGrantedBot(Long botConfigId, Long roomId, ChatRoomBot.ModerationGrant required) {
        if (botConfigId == null || roomId == null) {
            throw new AccessDeniedException("缺少机器人或聊天室上下文");
        }
        ChatRoomBot binding = chatRoomBotRepository.findByChatRoomIdAndBotConfigId(roomId, botConfigId)
                .orElseThrow(() -> new AccessDeniedException("机器人未加入该聊天室"));
        if (!Boolean.TRUE.equals(binding.getIsActive())) {
            throw new AccessDeniedException("机器人在该聊天室已禁用");
        }
        if (!grantAllows(binding.getModerationGrant(), required)) {
            throw new AccessDeniedException("机器人没有该管理权限");
        }
        return binding;
    }

    /** Monotonic: a higher grant implies all lower ones (NONE < MUTE < KICK). */
    private boolean grantAllows(ChatRoomBot.ModerationGrant have, ChatRoomBot.ModerationGrant need) {
        return have != null && have.ordinal() >= need.ordinal();
    }

    private void requireModeratableTarget(Long roomId, Long targetUserId) {
        if (!chatRoomRepository.isMember(roomId, targetUserId)) {
            throw new IllegalArgumentException("目标用户不是聊天室成员");
        }
        // A bot may never moderate a privileged member. isAdmin() already covers OWNER, but we
        // check isOwner explicitly for clarity / defense in depth.
        if (chatRoomRepository.isOwner(roomId, targetUserId) || chatRoomRepository.isAdmin(roomId, targetUserId)) {
            throw new AccessDeniedException("机器人不能对群主或管理员执行管理操作");
        }
    }
}
