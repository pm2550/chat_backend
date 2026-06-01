package com.chatapp.repository;

import com.chatapp.entity.MessageReaction;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReaction, Long> {

    Optional<MessageReaction> findByMessageIdAndUserIdAndEmoji(Long messageId, Long userId, String emoji);

    @EntityGraph(attributePaths = {"user"})
    List<MessageReaction> findByMessageId(Long messageId);

    @EntityGraph(attributePaths = {"user", "message"})
    List<MessageReaction> findByMessageIdIn(List<Long> messageIds);

    void deleteByMessageIdAndUserIdAndEmoji(Long messageId, Long userId, String emoji);
}
