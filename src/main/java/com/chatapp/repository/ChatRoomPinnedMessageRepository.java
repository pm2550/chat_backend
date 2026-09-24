package com.chatapp.repository;

import com.chatapp.entity.ChatRoomPinnedMessage;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatRoomPinnedMessageRepository extends JpaRepository<ChatRoomPinnedMessage, Long> {
    Optional<ChatRoomPinnedMessage> findByChatRoomIdAndMessageId(Long chatRoomId, Long messageId);

    void deleteByChatRoomIdAndMessageId(Long chatRoomId, Long messageId);

    // LOAD 而不是默认的 FETCH：FETCH 图会把没列出的 EAGER 字段（@提及列表）也当成懒加载，
    // 生产环境关了 open-in-view，控制器里转 DTO 时就会抛 LazyInitializationException，置顶直接失败。
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"message", "message.sender", "message.chatRoom", "message.anonymousIdentity", "message.botConfig", "message.replyToMessage", "message.replyToMessage.sender", "message.replyToMessage.anonymousIdentity"})
    List<ChatRoomPinnedMessage> findByChatRoomIdOrderByCreatedAtDesc(Long chatRoomId);
}
