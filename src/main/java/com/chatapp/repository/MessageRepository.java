package com.chatapp.repository;

import com.chatapp.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    Page<Message> findByChatRoomIdAndIsDeletedFalseOrderByCreatedAtDesc(Long chatRoomId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND m.id < :beforeMessageId ORDER BY m.createdAt DESC")
    Page<Message> findByChatRoomIdBeforeMessage(@Param("chatRoomId") Long chatRoomId,
                                               @Param("beforeMessageId") Long beforeMessageId,
                                               Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND m.id > :afterMessageId ORDER BY m.createdAt ASC")
    Page<Message> findByChatRoomIdAfterMessage(@Param("chatRoomId") Long chatRoomId,
                                              @Param("afterMessageId") Long afterMessageId,
                                              Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false ORDER BY m.createdAt DESC")
    List<Message> findLatestMessageByChatRoomId(@Param("chatRoomId") Long chatRoomId, Pageable pageable);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND m.id > :lastReadMessageId")
    long countUnreadMessages(@Param("chatRoomId") Long chatRoomId, @Param("lastReadMessageId") Long lastReadMessageId);

    Page<Message> findBySenderIdAndIsDeletedFalseOrderByCreatedAtDesc(Long senderId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND " +
           "LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY m.createdAt DESC")
    Page<Message> searchMessagesInChatRoom(@Param("chatRoomId") Long chatRoomId,
                                          @Param("keyword") String keyword,
                                          Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND " +
           "m.createdAt BETWEEN :startTime AND :endTime ORDER BY m.createdAt DESC")
    List<Message> findMessagesByDateRange(@Param("chatRoomId") Long chatRoomId,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime);

    Page<Message> findByChatRoomIdAndMessageTypeAndIsDeletedFalseOrderByCreatedAtDesc(
            Long chatRoomId, Message.MessageType messageType, Pageable pageable);

    long countByChatRoomIdAndIsDeletedFalse(Long chatRoomId);

    @Modifying
    @Query("DELETE FROM Message m WHERE m.selfDestructAt IS NOT NULL AND m.selfDestructAt <= :now")
    int deleteExpiredSelfDestructMessages(@Param("now") LocalDateTime now);

    // --- Methods required by MessageService ---

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false ORDER BY m.createdAt DESC")
    Page<Message> findByChatRoomIdOrderByCreatedAtDesc(@Param("chatRoomId") Long chatRoomId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false ORDER BY m.createdAt DESC")
    List<Message> findRecentMessagesList(@Param("chatRoomId") Long chatRoomId, Pageable pageable);

    default List<Message> findRecentMessages(Long chatRoomId, int limit) {
        return findRecentMessagesList(chatRoomId, PageRequest.of(0, limit));
    }

    @Modifying
    @Query("UPDATE Message m SET m.readCount = m.readCount + 1, m.messageStatus = 'READ' WHERE m.id = :messageId AND m.sender.id <> :userId")
    void markAsRead(@Param("messageId") Long messageId, @Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(crm.unreadCount), 0) FROM ChatRoomMember crm WHERE crm.user.id = :userId")
    Long countTotalUnreadMessages(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE Message m SET m.readCount = m.readCount + 1, m.messageStatus = 'READ' " +
           "WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND m.messageStatus <> 'READ' " +
           "AND m.sender.id <> :userId")
    void markAllAsReadInChatRoom(@Param("chatRoomId") Long chatRoomId, @Param("userId") Long userId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false")
    Long countByChatRoomId(@Param("chatRoomId") Long chatRoomId);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false ORDER BY m.createdAt DESC")
    List<Message> findLastMessages(@Param("chatRoomId") Long chatRoomId, Pageable pageable);

    default Message findLastMessage(Long chatRoomId) {
        List<Message> messages = findLastMessages(chatRoomId, PageRequest.of(0, 1));
        return messages.isEmpty() ? null : messages.get(0);
    }

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND " +
           "LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY m.createdAt DESC")
    Page<Message> searchInChatRoom(@Param("chatRoomId") Long chatRoomId, @Param("keyword") String keyword, Pageable pageable);
}
