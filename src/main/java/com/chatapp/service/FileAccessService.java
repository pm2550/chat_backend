package com.chatapp.service;

import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.StickerPackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 受保护文件（聊天附件 / AI 画图 / 贴纸图）的访问判定。
 *
 * 同一个文件 URL 可能被多条消息共用：转发会复制 fileUrl，贴纸消息直接引用贴纸图 URL。
 * 所以授权规则是“只要有任意一条未删除的引用消息落在查看者所在的房间就放行”，
 * 另外贴纸图还按贴纸包可见性放行（没发过的贴纸也要能在选择面板里显示）。
 */
@Service
@RequiredArgsConstructor
public class FileAccessService {

    private final MessageRepository messageRepository;
    private final StickerPackRepository stickerPackRepository;

    @Transactional(readOnly = true)
    public Decision authorize(String fileUrl, Long userId) {
        if (fileUrl == null || fileUrl.isBlank() || userId == null) {
            return Decision.notFound();
        }
        List<Message> granting = messageRepository.findActiveMessagesReferencingFileUrlVisibleTo(
                fileUrl, userId, PageRequest.of(0, 1));
        if (!granting.isEmpty()) {
            Message message = granting.get(0);
            return Decision.grantedByMessage(message.getId(), message.getChatRoom().getId());
        }

        // 贴纸：新贴纸在 /api/files/sticker/，老贴纸行仍是 /api/files/chat/ 地址，两种都按包可见性判断。
        List<Long> packIds = stickerPackRepository.findVisiblePackIdsReferencingUrl(
                fileUrl, userId, PageRequest.of(0, 1));
        if (!packIds.isEmpty()) {
            return Decision.grantedByStickerPack(packIds.get(0));
        }

        boolean known = messageRepository.existsActiveMessageReferencingFileUrl(fileUrl)
                || stickerPackRepository.existsReferencingUrl(fileUrl);
        return known ? Decision.forbidden() : Decision.notFound();
    }

    public enum Status { GRANTED, FORBIDDEN, NOT_FOUND }

    /**
     * messageId/roomId 为放行依据的消息（用于审计）；stickerPackId 表示按贴纸包可见性放行。
     */
    public record Decision(Status status, Long messageId, Long roomId, Long stickerPackId) {
        static Decision grantedByMessage(Long messageId, Long roomId) {
            return new Decision(Status.GRANTED, messageId, roomId, null);
        }

        static Decision grantedByStickerPack(Long packId) {
            return new Decision(Status.GRANTED, null, null, packId);
        }

        static Decision forbidden() {
            return new Decision(Status.FORBIDDEN, null, null, null);
        }

        static Decision notFound() {
            return new Decision(Status.NOT_FOUND, null, null, null);
        }

        public boolean granted() {
            return status == Status.GRANTED;
        }

        public boolean grantedByMessage() {
            return granted() && messageId != null;
        }
    }
}
