package com.chatapp.repository;

import com.chatapp.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息Repository接口
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * 根据聊天室ID查找消息（分页）
     */
    Page<Message> findByChatRoomIdAndIsDeletedFalseOrderByCreatedAtDesc(Long chatRoomId, Pageable pageable);

    /**
     * 根据聊天室ID查找消息（在指定消息之前）
     */
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND m.id < :beforeMessageId ORDER BY m.createdAt DESC")
    Page<Message> findByChatRoomIdBeforeMessage(@Param("chatRoomId") Long chatRoomId, 
                                               @Param("beforeMessageId") Long beforeMessageId, 
                                               Pageable pageable);

    /**
     * 根据聊天室ID查找消息（在指定消息之后）
     */
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND m.id > :afterMessageId ORDER BY m.createdAt ASC")
    Page<Message> findByChatRoomIdAfterMessage(@Param("chatRoomId") Long chatRoomId, 
                                              @Param("afterMessageId") Long afterMessageId, 
                                              Pageable pageable);

    /**
     * 查找聊天室的最新消息
     */
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false ORDER BY m.createdAt DESC")
    List<Message> findLatestMessageByChatRoomId(@Param("chatRoomId") Long chatRoomId, Pageable pageable);

    /**
     * 统计聊天室未读消息数量
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND m.id > :lastReadMessageId")
    long countUnreadMessages(@Param("chatRoomId") Long chatRoomId, @Param("lastReadMessageId") Long lastReadMessageId);

    /**
     * 根据发送者查找消息
     */
    Page<Message> findBySenderIdAndIsDeletedFalseOrderByCreatedAtDesc(Long senderId, Pageable pageable);

    /**
     * 搜索消息内容
     */
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND " +
           "LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY m.createdAt DESC")
    Page<Message> searchMessagesInChatRoom(@Param("chatRoomId") Long chatRoomId, 
                                          @Param("keyword") String keyword, 
                                          Pageable pageable);

    /**
     * 查找指定时间范围内的消息
     */
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND " +
           "m.createdAt BETWEEN :startTime AND :endTime ORDER BY m.createdAt DESC")
    List<Message> findMessagesByDateRange(@Param("chatRoomId") Long chatRoomId,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime);

    /**
     * 根据消息类型查找消息
     */
    Page<Message> findByChatRoomIdAndMessageTypeAndIsDeletedFalseOrderByCreatedAtDesc(
            Long chatRoomId, Message.MessageType messageType, Pageable pageable);

    /**
     * 统计聊天室消息总数
     */
    long countByChatRoomIdAndIsDeletedFalse(Long chatRoomId);
} 