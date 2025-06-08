package com.chatapp.repository;

import com.chatapp.entity.ChatHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 聊天记录Repository接口
 */
@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {

    /**
     * 获取私聊消息记录（分页）
     */
    @Query("SELECT ch FROM ChatHistory ch WHERE " +
           "((ch.senderId = :userId1 AND ch.receiverId = :userId2) OR " +
           "(ch.senderId = :userId2 AND ch.receiverId = :userId1)) AND " +
           "ch.chatRoomId IS NULL AND ch.isDeleted = false " +
           "ORDER BY ch.sentAt DESC")
    Page<ChatHistory> findPrivateChatHistory(@Param("userId1") Long userId1, 
                                           @Param("userId2") Long userId2, 
                                           Pageable pageable);

    /**
     * 获取群聊消息记录（分页）
     */
    @Query("SELECT ch FROM ChatHistory ch WHERE " +
           "ch.chatRoomId = :chatRoomId AND ch.isDeleted = false " +
           "ORDER BY ch.sentAt DESC")
    Page<ChatHistory> findGroupChatHistory(@Param("chatRoomId") Long chatRoomId, 
                                         Pageable pageable);

    /**
     * 获取私聊最新消息
     */
    @Query("SELECT ch FROM ChatHistory ch WHERE " +
           "((ch.senderId = :userId1 AND ch.receiverId = :userId2) OR " +
           "(ch.senderId = :userId2 AND ch.receiverId = :userId1)) AND " +
           "ch.chatRoomId IS NULL AND ch.isDeleted = false " +
           "ORDER BY ch.sentAt DESC")
    List<ChatHistory> findLatestPrivateMessages(@Param("userId1") Long userId1, 
                                               @Param("userId2") Long userId2,
                                               Pageable pageable);

    /**
     * 获取群聊最新消息
     */
    @Query("SELECT ch FROM ChatHistory ch WHERE " +
           "ch.chatRoomId = :chatRoomId AND ch.isDeleted = false " +
           "ORDER BY ch.sentAt DESC")
    List<ChatHistory> findLatestGroupMessages(@Param("chatRoomId") Long chatRoomId,
                                            Pageable pageable);

    /**
     * 搜索私聊消息
     */
    @Query("SELECT ch FROM ChatHistory ch WHERE " +
           "((ch.senderId = :userId1 AND ch.receiverId = :userId2) OR " +
           "(ch.senderId = :userId2 AND ch.receiverId = :userId1)) AND " +
           "ch.chatRoomId IS NULL AND ch.isDeleted = false AND " +
           "LOWER(ch.content) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "ORDER BY ch.sentAt DESC")
    Page<ChatHistory> searchPrivateChatHistory(@Param("userId1") Long userId1, 
                                             @Param("userId2") Long userId2,
                                             @Param("keyword") String keyword,
                                             Pageable pageable);

    /**
     * 搜索群聊消息
     */
    @Query("SELECT ch FROM ChatHistory ch WHERE " +
           "ch.chatRoomId = :chatRoomId AND ch.isDeleted = false AND " +
           "LOWER(ch.content) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "ORDER BY ch.sentAt DESC")
    Page<ChatHistory> searchGroupChatHistory(@Param("chatRoomId") Long chatRoomId,
                                           @Param("keyword") String keyword,
                                           Pageable pageable);

    /**
     * 获取用户的所有聊天记录（最近联系人）
     */
    @Query("SELECT ch FROM ChatHistory ch WHERE " +
           "(ch.senderId = :userId OR ch.receiverId = :userId OR " +
           "ch.chatRoomId IN (SELECT crm.chatRoom.id FROM ChatRoomMember crm WHERE crm.user.id = :userId)) AND " +
           "ch.isDeleted = false " +
           "ORDER BY ch.sentAt DESC")
    Page<ChatHistory> findUserChatHistory(@Param("userId") Long userId, Pageable pageable);

    /**
     * 根据时间范围获取消息
     */
    @Query("SELECT ch FROM ChatHistory ch WHERE " +
           "((ch.senderId = :userId1 AND ch.receiverId = :userId2) OR " +
           "(ch.senderId = :userId2 AND ch.receiverId = :userId1)) AND " +
           "ch.chatRoomId IS NULL AND ch.isDeleted = false AND " +
           "ch.sentAt BETWEEN :startTime AND :endTime " +
           "ORDER BY ch.sentAt ASC")
    List<ChatHistory> findPrivateMessagesInTimeRange(@Param("userId1") Long userId1,
                                                    @Param("userId2") Long userId2,
                                                    @Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime);

    /**
     * 获取群聊时间范围内的消息
     */
    @Query("SELECT ch FROM ChatHistory ch WHERE " +
           "ch.chatRoomId = :chatRoomId AND ch.isDeleted = false AND " +
           "ch.sentAt BETWEEN :startTime AND :endTime " +
           "ORDER BY ch.sentAt ASC")
    List<ChatHistory> findGroupMessagesInTimeRange(@Param("chatRoomId") Long chatRoomId,
                                                  @Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime);

    /**
     * 统计私聊消息数量
     */
    @Query("SELECT COUNT(ch) FROM ChatHistory ch WHERE " +
           "((ch.senderId = :userId1 AND ch.receiverId = :userId2) OR " +
           "(ch.senderId = :userId2 AND ch.receiverId = :userId1)) AND " +
           "ch.chatRoomId IS NULL AND ch.isDeleted = false")
    long countPrivateMessages(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    /**
     * 统计群聊消息数量
     */
    @Query("SELECT COUNT(ch) FROM ChatHistory ch WHERE " +
           "ch.chatRoomId = :chatRoomId AND ch.isDeleted = false")
    long countGroupMessages(@Param("chatRoomId") Long chatRoomId);

    /**
     * 根据ID查找未删除的消息
     */
    Optional<ChatHistory> findByIdAndIsDeletedFalse(Long id);

    /**
     * 获取用户发送的消息
     */
    List<ChatHistory> findBySenderIdAndIsDeletedFalseOrderBySentAtDesc(Long senderId, Pageable pageable);

    /**
     * 获取包含特定文件类型的消息
     */
    @Query("SELECT ch FROM ChatHistory ch WHERE " +
           "ch.messageType = :messageType AND ch.isDeleted = false " +
           "ORDER BY ch.sentAt DESC")
    Page<ChatHistory> findByMessageTypeAndIsDeletedFalse(@Param("messageType") ChatHistory.MessageType messageType,
                                                        Pageable pageable);

    /**
     * 清理指定时间之前的消息
     */
    @Query("UPDATE ChatHistory ch SET ch.isDeleted = true, ch.deletedAt = CURRENT_TIMESTAMP " +
           "WHERE ch.sentAt < :beforeTime AND ch.isDeleted = false")
    int markMessagesAsDeletedBefore(@Param("beforeTime") LocalDateTime beforeTime);
} 