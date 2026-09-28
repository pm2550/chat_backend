package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Selects at most one relevant room image without changing the bot's LLM provider. */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotVisionAttachmentSelector {
    private static final int CANDIDATE_LIMIT = 20;
    private static final int AUTO_LOOKBACK_HOURS = 24;
    /** 去掉 @ 之后不超过这么多字的追问（"可爱吗""咋样"），才会接上紧挨着的那张图。 */
    private static final int SHORT_FOLLOW_UP_CHARS = 20;
    private static final Pattern MENTION = Pattern.compile("@\\S+");
    private static final Pattern VISUAL_REFERENCE = Pattern.compile(
            "(?iu)(这张|那张|这幅|那幅|图里|图片|照片|截图|画面|画作|作品|佳作|看图|"
                    + "(?:刚才|前面|上面)(?:的)?(?:图|图片|照片|截图|画|画作|作品)|"
                    + "第[一二三四五六七八九十\\d]+张)");

    private final MessageRepository messageRepository;
    private final AgentVisionAttachmentService visionAttachmentService;

    public Selection select(BotConfig bot, Long roomId, Message sourceMessage, String prompt) {
        if (bot == null || Boolean.FALSE.equals(bot.getVisionInputEnabled())) {
            return Selection.empty();
        }

        Message selected = directImage(sourceMessage);
        String reason = "current_message";
        if (selected == null) {
            selected = repliedImage(roomId, sourceMessage);
            reason = "reply_target";
        }
        if (selected == null
                && !Boolean.FALSE.equals(bot.getHistoryImageInspectionEnabled())
                && referencesImage(prompt)) {
            selected = recentReferencedImage(roomId, sourceMessage, prompt);
            reason = "recent_room_image";
        }
        if (selected == null
                && !Boolean.FALSE.equals(bot.getHistoryImageInspectionEnabled())
                && isShortFollowUp(prompt)) {
            selected = precedingImageFromSameSender(roomId, sourceMessage);
            reason = "preceding_image";
        }
        if (selected == null) {
            return Selection.empty();
        }

        AgentVisionAttachmentService.ImageContext image = visionAttachmentService.resolve(selected, true);
        if (image == null || image.attachments().isEmpty()) {
            log.warn("Bot vision selection failed botId={} roomId={} messageId={} reason={}",
                    bot.getId(), roomId, selected.getId(), reason);
            return image == null
                    ? Selection.empty()
                    : new Selection(image, selected.getId(), reason);
        }
        log.info("Bot vision selected botId={} provider={} roomId={} messageId={} reason={} images={}",
                bot.getId(), bot.getLlmProvider(), roomId, selected.getId(), reason, image.attachments().size());
        return new Selection(image, selected.getId(), reason);
    }

    private Message directImage(Message sourceMessage) {
        return visionAttachmentService.isImageMessage(sourceMessage) ? sourceMessage : null;
    }

    private Message repliedImage(Long roomId, Message sourceMessage) {
        if (sourceMessage == null || sourceMessage.getReplyToMessage() == null) {
            return null;
        }
        Message replied = sourceMessage.getReplyToMessage();
        return belongsToRoom(replied, roomId) && visionAttachmentService.isImageMessage(replied)
                ? replied
                : null;
    }

    private Message recentReferencedImage(Long roomId, Message sourceMessage, String prompt) {
        if (roomId == null) {
            return null;
        }
        List<Message> candidates = messageRepository
                .findFileMessagesInChatRoom(roomId, null, PageRequest.of(0, CANDIDATE_LIMIT))
                .getContent().stream()
                .filter(visionAttachmentService::isImageMessage)
                .filter(message -> sourceMessage == null || sourceMessage.getId() == null
                        || message.getId() == null || message.getId() < sourceMessage.getId())
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }

        String normalizedPrompt = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        Message named = candidates.stream()
                .filter(message -> message.getFileName() != null
                        && !message.getFileName().isBlank()
                        && normalizedPrompt.contains(message.getFileName().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
        if (named != null) {
            return named;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusHours(AUTO_LOOKBACK_HOURS);
        return candidates.stream()
                .filter(message -> message.getCreatedAt() == null || !message.getCreatedAt().isBefore(cutoff))
                .findFirst()
                .orElse(null);
    }

    /**
     * 发完图紧接着问一句"可爱吗"：问的就是刚发的那张图，即使话里没有"图片 / 照片"。
     * 只看房间里紧挨着的上一条消息，而且必须是同一个人发的图，别人的图或隔了别的话都不算。
     */
    private Message precedingImageFromSameSender(Long roomId, Message sourceMessage) {
        if (roomId == null || sourceMessage == null || sourceMessage.getId() == null
                || sourceMessage.getSender() == null || sourceMessage.getSender().getId() == null) {
            return null;
        }
        Page<Message> before = messageRepository.findByChatRoomIdBeforeMessage(
                roomId, sourceMessage.getId(), PageRequest.of(0, 1));
        if (before == null || before.isEmpty()) {
            return null;
        }
        Message previous = before.getContent().get(0);
        LocalDateTime cutoff = LocalDateTime.now().minusHours(AUTO_LOOKBACK_HOURS);
        boolean sameSender = previous.getSender() != null
                && Objects.equals(previous.getSender().getId(), sourceMessage.getSender().getId())
                && previous.getBotConfig() == null;
        boolean recent = previous.getCreatedAt() == null || !previous.getCreatedAt().isBefore(cutoff);
        return sameSender && recent && visionAttachmentService.isImageMessage(previous)
                ? previous
                : null;
    }

    private boolean isShortFollowUp(String prompt) {
        if (prompt == null) {
            return false;
        }
        String text = MENTION.matcher(prompt).replaceAll("").strip();
        // 只 @ 了一下（空）也算：规则里本来就要求这时去接上一条。
        return text.codePointCount(0, text.length()) <= SHORT_FOLLOW_UP_CHARS;
    }

    private boolean referencesImage(String prompt) {
        return prompt != null && VISUAL_REFERENCE.matcher(prompt).find();
    }

    private boolean belongsToRoom(Message message, Long roomId) {
        return message != null
                && message.getChatRoom() != null
                && Objects.equals(message.getChatRoom().getId(), roomId)
                && !Boolean.TRUE.equals(message.getIsDeleted());
    }

    public record Selection(
            AgentVisionAttachmentService.ImageContext image,
            Long messageId,
            String reason) {
        static Selection empty() {
            return new Selection(AgentVisionAttachmentService.ImageContext.empty(), null, "none");
        }
    }
}
