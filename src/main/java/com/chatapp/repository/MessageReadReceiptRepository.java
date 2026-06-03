package com.chatapp.repository;

import com.chatapp.entity.MessageReadReceipt;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageReadReceiptRepository extends JpaRepository<MessageReadReceipt, Long> {
    Optional<MessageReadReceipt> findByMessageIdAndUserId(Long messageId, Long userId);
    @EntityGraph(attributePaths = {"user"})
    List<MessageReadReceipt> findByMessageIdOrderByReadAtAsc(Long messageId);
    long countByMessageId(Long messageId);
}
