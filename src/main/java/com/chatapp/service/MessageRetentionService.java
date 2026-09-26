package com.chatapp.service;

import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.StickerPackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageRetentionService {
    private static final String EXPIRED_MESSAGE_CONTENT = "[消息已过期]";

    private final MessageRepository messageRepository;
    private final FileStorageService fileStorageService;
    private final StickerPackRepository stickerPackRepository;

    @Value("${message.retention.enabled:true}")
    private boolean enabled;

    @Value("${message.retention.days:30}")
    private long retentionDays;

    @Value("${message.retention.batch-size:500}")
    private int batchSize;

    @Scheduled(cron = "${message.retention.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public CleanupResult cleanupExpiredMessages() {
        if (!enabled) {
            log.debug("消息过期清理已关闭");
            return CleanupResult.disabledResult();
        }
        if (retentionDays <= 0) {
            log.warn("message.retention.days={} 非法，跳过消息过期清理", retentionDays);
            return CleanupResult.disabledResult();
        }

        int safeBatchSize = Math.max(1, batchSize);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        Set<String> seenFiles = new HashSet<>();
        int expiredMessages = 0;
        int deletedFiles = 0;
        int orphanImageFiles = 0;

        while (true) {
            Page<Message> page = messageRepository.findExpiredForRetention(
                    cutoff,
                    PageRequest.of(0, safeBatchSize));
            List<Message> messages = page.getContent();
            if (messages.isEmpty()) {
                break;
            }

            Set<String> batchFiles = new LinkedHashSet<>();
            for (Message message : messages) {
                collectMessageScopedFiles(message, batchFiles);
                expireMessage(message);
            }
            messageRepository.saveAll(messages);
            expiredMessages += messages.size();
            // 先把整批消息标成过期再判断引用：转发副本、贴纸消息会和别的消息共用同一个文件，
            // 只有没有任何未删除消息 / 贴纸包再引用时才能删盘上的文件。
            for (String fileUrl : batchFiles) {
                deletedFiles += deleteFileIfUnreferenced(fileUrl, seenFiles);
            }

            if (messages.size() < safeBatchSize) {
                break;
            }
        }

        orphanImageFiles = cleanupExpiredOrphanImageGenFiles(cutoff, safeBatchSize, seenFiles);
        CleanupResult result = new CleanupResult(expiredMessages, deletedFiles, orphanImageFiles, false);
        if (result.hasWork()) {
            log.info("消息过期清理完成: expiredMessages={}, deletedFiles={}, orphanImageFiles={}, retentionDays={}",
                    expiredMessages, deletedFiles, orphanImageFiles, retentionDays);
        }
        return result;
    }

    private void collectMessageScopedFiles(Message message, Set<String> batchFiles) {
        for (String fileUrl : new String[] {
                message.getFileUrl(), message.getImageGenUrl(), message.getThumbnailUrl(),
                message.getPreviewUrl()}) {
            if (isMessageScopedFile(fileUrl)) {
                batchFiles.add(fileUrl);
            }
        }
    }

    private int cleanupExpiredOrphanImageGenFiles(LocalDateTime cutoff, int maxFiles, Set<String> seenFiles) {
        int deleted = 0;
        try {
            for (String fileUrl : fileStorageService.listExpiredImageGenFileUrls(cutoff, maxFiles)) {
                if (!seenFiles.add(fileUrl)) {
                    continue;
                }
                if (!messageRepository.existsActiveMessageReferencingFileUrl(fileUrl)
                        && fileStorageService.deleteFile(fileUrl)) {
                    deleted++;
                }
            }
        } catch (IOException e) {
            log.warn("AI 画图孤儿文件清理失败: {}", e.getMessage());
        }
        return deleted;
    }

    private int deleteFileIfUnreferenced(String fileUrl, Set<String> seenFiles) {
        if (seenFiles.contains(fileUrl)) {
            return 0;
        }
        if (messageRepository.existsActiveMessageReferencingFileUrl(fileUrl)
                || stickerPackRepository.existsReferencingUrl(fileUrl)) {
            // 仍被引用：不记入 seenFiles，后面批次过期最后一条引用时还要再判断一次。
            return 0;
        }
        seenFiles.add(fileUrl);
        boolean deleted = fileStorageService.deleteFile(fileUrl);
        if (!deleted) {
            log.debug("消息过期清理未删除文件，可能已不存在或路径不受支持: {}", fileUrl);
        }
        return deleted ? 1 : 0;
    }

    private boolean isMessageScopedFile(String fileUrl) {
        return fileUrl != null
                && (fileUrl.startsWith("/api/files/chat/")
                || fileUrl.startsWith("/api/files/image-gen/"));
    }

    private void expireMessage(Message message) {
        message.setIsDeleted(true);
        message.setContent(EXPIRED_MESSAGE_CONTENT);
        message.setFileUrl(null);
        message.setFileName(null);
        message.setFileType(null);
        message.setFileSize(null);
        message.setThumbnailUrl(null);
        message.setPreviewUrl(null);
        message.setImageGenUrl(null);
        message.setImageGenProviderTaskId(null);
        message.setEncryptedContent(null);
        message.setLinkPreviewJson(null);
    }

    public record CleanupResult(
            int expiredMessages,
            int deletedFiles,
            int orphanImageFiles,
            boolean disabled) {
        static CleanupResult disabledResult() {
            return new CleanupResult(0, 0, 0, true);
        }

        public boolean hasWork() {
            return expiredMessages > 0 || deletedFiles > 0 || orphanImageFiles > 0;
        }
    }
}
