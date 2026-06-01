package com.chatapp.repository;

import com.chatapp.entity.MessageStar;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MessageStarRepository extends JpaRepository<MessageStar, Long> {
    Optional<MessageStar> findByMessageIdAndUserId(Long messageId, Long userId);

    void deleteByMessageIdAndUserId(Long messageId, Long userId);

    boolean existsByMessageIdAndUserId(Long messageId, Long userId);

    @EntityGraph(attributePaths = {"message", "message.sender", "message.chatRoom", "message.anonymousIdentity", "message.botConfig"})
    Page<MessageStar> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
