package com.chatapp.service;

import com.chatapp.entity.BotConfig;
import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private static final Pattern VISUAL_REFERENCE = Pattern.compile(
            "(?iu)(这张|这个|刚才|前面|上面|图里|图片|照片|截图|画面|画作|作品|佳作|人物|构图|光影|色彩|颜色|"
                    + "好看|可爱|漂亮|性感|女人|少女|男人|男孩|是什么|看图|评价|点评|分析|第[一二三四五\\d]+张)");

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
