package com.chatapp.repository;

import com.chatapp.entity.MessageStar;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MessageStarRepository extends JpaRepository<MessageStar, Long> {
    Optional<MessageStar> findByMessageIdAndUserId(Long messageId, Long userId);

    void deleteByMessageIdAndUserId(Long messageId, Long userId);

    boolean existsByMessageIdAndUserId(Long messageId, Long userId);

    @EntityGraph(attributePaths = {"message", "message.sender", "message.chatRoom", "message.anonymousIdentity", "message.botConfig"})
    Page<MessageStar> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query("SELECT s.message.id FROM MessageStar s WHERE s.user.id = :userId AND s.message.id IN :messageIds")
    List<Long> findStarredMessageIds(@Param("userId") Long userId,
                                     @Param("messageIds") Collection<Long> messageIds);
}
