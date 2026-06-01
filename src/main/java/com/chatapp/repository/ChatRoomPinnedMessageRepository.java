package com.chatapp.repository;

import com.chatapp.entity.ChatRoomPinnedMessage;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatRoomPinnedMessageRepository extends JpaRepository<ChatRoomPinnedMessage, Long> {
    Optional<ChatRoomPinnedMessage> findByChatRoomIdAndMessageId(Long chatRoomId, Long messageId);

    void deleteByChatRoomIdAndMessageId(Long chatRoomId, Long messageId);

    @EntityGraph(attributePaths = {"message", "message.sender", "message.chatRoom", "message.anonymousIdentity", "message.botConfig"})
    List<ChatRoomPinnedMessage> findByChatRoomIdOrderByCreatedAtDesc(Long chatRoomId);
}
