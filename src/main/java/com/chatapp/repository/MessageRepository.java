package com.chatapp.repository;

import com.chatapp.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    Page<Message> findByChatRoomIdAndIsDeletedFalseOrderByCreatedAtDesc(Long chatRoomId, Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig", "forwardedFromMessage"})
    Optional<Message> findWithSenderById(Long id);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND m.id < :beforeMessageId ORDER BY m.createdAt DESC")
    Page<Message> findByChatRoomIdBeforeMessage(@Param("chatRoomId") Long chatRoomId,
                                               @Param("beforeMessageId") Long beforeMessageId,
                                               Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND m.id > :afterMessageId ORDER BY m.createdAt ASC")
    Page<Message> findByChatRoomIdAfterMessage(@Param("chatRoomId") Long chatRoomId,
                                              @Param("afterMessageId") Long afterMessageId,
                                              Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false ORDER BY m.createdAt DESC")
    List<Message> findLatestMessageByChatRoomId(@Param("chatRoomId") Long chatRoomId, Pageable pageable);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND m.id > :lastReadMessageId")
    long countUnreadMessages(@Param("chatRoomId") Long chatRoomId, @Param("lastReadMessageId") Long lastReadMessageId);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    Page<Message> findBySenderIdAndIsDeletedFalseOrderByCreatedAtDesc(Long senderId, Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND " +
           "LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) AND m.encryptedContent IS NULL ORDER BY m.createdAt DESC")
    Page<Message> searchMessagesInChatRoom(@Param("chatRoomId") Long chatRoomId,
                                          @Param("keyword") String keyword,
                                          Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND " +
           "m.createdAt BETWEEN :startTime AND :endTime ORDER BY m.createdAt DESC")
    List<Message> findMessagesByDateRange(@Param("chatRoomId") Long chatRoomId,
                                         @Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    Page<Message> findByChatRoomIdAndMessageTypeAndIsDeletedFalseOrderByCreatedAtDesc(
            Long chatRoomId, Message.MessageType messageType, Pageable pageable);

    long countByChatRoomIdAndIsDeletedFalse(Long chatRoomId);

    @Modifying
    @Query("DELETE FROM Message m WHERE m.selfDestructAt IS NOT NULL AND m.selfDestructAt <= :now")
    int deleteExpiredSelfDestructMessages(@Param("now") LocalDateTime now);

    // --- Methods required by MessageService ---

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false ORDER BY m.createdAt DESC")
    Page<Message> findByChatRoomIdOrderByCreatedAtDesc(@Param("chatRoomId") Long chatRoomId, Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false " +
           "AND (:clearedBeforeMessageId IS NULL OR m.id > :clearedBeforeMessageId) ORDER BY m.createdAt DESC")
    Page<Message> findByChatRoomIdAfterClear(@Param("chatRoomId") Long chatRoomId,
                                             @Param("clearedBeforeMessageId") Long clearedBeforeMessageId,
                                             Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false ORDER BY m.createdAt DESC")
    List<Message> findRecentMessagesList(@Param("chatRoomId") Long chatRoomId, Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false " +
           "AND (:clearedBeforeMessageId IS NULL OR m.id > :clearedBeforeMessageId) ORDER BY m.createdAt DESC")
    List<Message> findRecentMessagesListAfterClear(@Param("chatRoomId") Long chatRoomId,
                                                   @Param("clearedBeforeMessageId") Long clearedBeforeMessageId,
                                                   Pageable pageable);

    default List<Message> findRecentMessages(Long chatRoomId, int limit) {
        return findRecentMessagesList(chatRoomId, PageRequest.of(0, limit));
    }

    @Query("SELECT COALESCE(SUM(crm.unreadCount), 0) FROM ChatRoomMember crm WHERE crm.user.id = :userId " +
           "AND COALESCE(crm.isBlocked, false) = false")
    Long countTotalUnreadMessages(@Param("userId") Long userId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false")
    Long countByChatRoomId(@Param("chatRoomId") Long chatRoomId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false " +
           "AND (:clearedBeforeMessageId IS NULL OR m.id > :clearedBeforeMessageId)")
    Long countByChatRoomIdAfterClear(@Param("chatRoomId") Long chatRoomId,
                                     @Param("clearedBeforeMessageId") Long clearedBeforeMessageId);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false ORDER BY m.createdAt DESC")
    List<Message> findLastMessages(@Param("chatRoomId") Long chatRoomId, Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false " +
           "AND (:clearedBeforeMessageId IS NULL OR m.id > :clearedBeforeMessageId) ORDER BY m.createdAt DESC")
    List<Message> findLastMessagesAfterClear(@Param("chatRoomId") Long chatRoomId,
                                             @Param("clearedBeforeMessageId") Long clearedBeforeMessageId,
                                             Pageable pageable);

    default Message findLastMessage(Long chatRoomId) {
        List<Message> messages = findLastMessages(chatRoomId, PageRequest.of(0, 1));
        return messages.isEmpty() ? null : messages.get(0);
    }

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {
            "sender", "chatRoom", "anonymousIdentity", "botConfig",
            "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"
    })
    @Query("SELECT m FROM Message m WHERE m.id IN (" +
           "SELECT MAX(m2.id) FROM Message m2, ChatRoomMember crm " +
           "WHERE crm.user.id = :userId AND crm.chatRoom = m2.chatRoom " +
           "AND m2.chatRoom.id IN :roomIds AND m2.isDeleted = false " +
           "AND (crm.clearedBeforeMessageId IS NULL OR m2.id > crm.clearedBeforeMessageId) " +
           "GROUP BY m2.chatRoom.id)")
    List<Message> findLatestVisibleMessagesForRooms(@Param("userId") Long userId,
                                                     @Param("roomIds") List<Long> roomIds);

    // 所有关键词搜索都排除端到端加密消息：服务器只有占位文字，搜"加密"会把它们全搜出来。
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false AND " +
           "LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) AND m.encryptedContent IS NULL ORDER BY m.createdAt DESC")
    Page<Message> searchInChatRoom(@Param("chatRoomId") Long chatRoomId, @Param("keyword") String keyword, Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false " +
           "AND (:clearedBeforeMessageId IS NULL OR m.id > :clearedBeforeMessageId) " +
           "AND LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) AND m.encryptedContent IS NULL " +
           "ORDER BY m.createdAt DESC")
    Page<Message> searchInChatRoomAfterClear(@Param("chatRoomId") Long chatRoomId,
                                             @Param("keyword") String keyword,
                                             @Param("clearedBeforeMessageId") Long clearedBeforeMessageId,
                                             Pageable pageable);

    /**
     * 跨房间全局搜索：只搜当前用户所在、未屏蔽的活跃房间，并遵守每个成员自己的清空记录起点。
     */
    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query(value = "SELECT m FROM Message m, ChatRoomMember crm " +
                   "WHERE crm.user.id = :userId AND crm.chatRoom = m.chatRoom " +
                   "AND m.chatRoom.isActive = true " +
                   "AND COALESCE(crm.isBlocked, false) = false " +
                   "AND m.isDeleted = false " +
                   "AND (crm.clearedBeforeMessageId IS NULL OR m.id > crm.clearedBeforeMessageId) " +
                   "AND LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) AND m.encryptedContent IS NULL " +
                   "ORDER BY m.createdAt DESC, m.id DESC",
           countQuery = "SELECT COUNT(m) FROM Message m, ChatRoomMember crm " +
                        "WHERE crm.user.id = :userId AND crm.chatRoom = m.chatRoom " +
                        "AND m.chatRoom.isActive = true " +
                        "AND COALESCE(crm.isBlocked, false) = false " +
                        "AND m.isDeleted = false " +
                        "AND (crm.clearedBeforeMessageId IS NULL OR m.id > crm.clearedBeforeMessageId) " +
                        "AND LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) AND m.encryptedContent IS NULL")
    Page<Message> searchInUserChatRooms(@Param("userId") Long userId,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false " +
           "AND m.createdAt < :createdAt ORDER BY m.createdAt DESC")
    List<Message> findContextBefore(@Param("chatRoomId") Long chatRoomId,
                                    @Param("createdAt") LocalDateTime createdAt,
                                    Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false " +
           "AND m.createdAt > :createdAt ORDER BY m.createdAt ASC")
    List<Message> findContextAfter(@Param("chatRoomId") Long chatRoomId,
                                   @Param("createdAt") LocalDateTime createdAt,
                                   Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false " +
           "AND m.fileUrl IS NOT NULL AND (:messageType IS NULL OR m.messageType = :messageType) " +
           "ORDER BY m.createdAt DESC")
    Page<Message> findFileMessagesInChatRoom(@Param("chatRoomId") Long chatRoomId,
                                             @Param("messageType") Message.MessageType messageType,
                                             Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT DISTINCT m FROM Message m JOIN m.mentionedUserIds mentionedUserId " +
           "WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false " +
           "AND mentionedUserId = :userId ORDER BY m.createdAt DESC")
    Page<Message> findMentionedMessagesForUser(@Param("chatRoomId") Long chatRoomId,
                                               @Param("userId") Long userId,
                                               Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT DISTINCT m FROM Message m JOIN m.mentionedUserIds mentionedUserId " +
           "WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false " +
           "AND (:clearedBeforeMessageId IS NULL OR m.id > :clearedBeforeMessageId) " +
           "AND mentionedUserId = :userId " +
           "ORDER BY m.createdAt DESC")
    Page<Message> findMentionedMessagesForUserAfterClear(@Param("chatRoomId") Long chatRoomId,
                                                         @Param("userId") Long userId,
                                                         @Param("clearedBeforeMessageId") Long clearedBeforeMessageId,
                                                         Pageable pageable);

    @EntityGraph(type = EntityGraph.EntityGraphType.LOAD, attributePaths = {"sender", "chatRoom", "anonymousIdentity", "botConfig", "replyToMessage", "replyToMessage.sender", "replyToMessage.anonymousIdentity", "replyToMessage.botConfig"})
    @Query("SELECT m FROM Message m WHERE m.chatRoom.id = :chatRoomId AND m.isDeleted = false " +
           "AND (:clearedBeforeMessageId IS NULL OR m.id > :clearedBeforeMessageId) " +
           "AND m.fileUrl IS NOT NULL " +
           "AND (:messageType IS NULL OR m.messageType = :messageType) ORDER BY m.createdAt DESC")
    Page<Message> findFileMessagesInChatRoomAfterClear(@Param("chatRoomId") Long chatRoomId,
                                                       @Param("messageType") Message.MessageType messageType,
                                                       @Param("clearedBeforeMessageId") Long clearedBeforeMessageId,
                                                       Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.isDeleted = false AND m.createdAt < :cutoff ORDER BY m.createdAt ASC")
    Page<Message> findExpiredForRetention(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END " +
           "FROM Message m WHERE m.isDeleted = false " +
           "AND (m.fileUrl = :fileUrl OR m.imageGenUrl = :fileUrl " +
           "     OR m.thumbnailUrl = :fileUrl OR m.previewUrl = :fileUrl)")
    boolean existsActiveMessageReferencingFileUrl(@Param("fileUrl") String fileUrl);

    /** 不论删没删，还有没有消息引用这个地址（回填换掉老缩略图后，据此决定能不能删文件）。 */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END " +
           "FROM Message m WHERE m.fileUrl = :fileUrl OR m.imageGenUrl = :fileUrl " +
           "OR m.thumbnailUrl = :fileUrl OR m.previewUrl = :fileUrl")
    boolean existsAnyMessageReferencingFileUrl(@Param("fileUrl") String fileUrl);

    /**
     * 回填预览图的候选：明文、未删除、不太小的图片消息（聊天图片、AI 画图），服务器预览图版本低于 :version
     * （NULL = 没有缩略图，或 1.1.51 的 400px 缩略图）。GIF 按动图不做缩略图直接排除。
     * 按 id 升序、从 afterId 之后取，一批一批往后走。
     */
    @Query("SELECT m FROM Message m WHERE m.isDeleted = false " +
           "AND (m.renditionVersion IS NULL OR m.renditionVersion < :version) " +
           "AND m.encryptedContent IS NULL AND m.fileUrl IS NOT NULL " +
           "AND m.messageType IN :types " +
           "AND (m.fileSize IS NULL OR m.fileSize > :minBytes) " +
           "AND (m.fileType IS NULL OR LOWER(m.fileType) <> 'image/gif') " +
           "AND m.id > :afterId ORDER BY m.id ASC")
    List<Message> findThumbnailBackfillCandidates(@Param("types") Collection<Message.MessageType> types,
                                                  @Param("minBytes") Long minBytes,
                                                  @Param("version") Integer version,
                                                  @Param("afterId") Long afterId,
                                                  Pageable pageable);

    /** 同一个原图（含转发副本）的明文消息现在引用的缩略图/中图地址，回填换新后要删掉老文件。 */
    @Query("SELECT DISTINCT m.thumbnailUrl FROM Message m WHERE m.fileUrl = :fileUrl " +
           "AND m.encryptedContent IS NULL AND m.thumbnailUrl IS NOT NULL")
    List<String> findThumbnailUrlsForFileUrl(@Param("fileUrl") String fileUrl);

    @Query("SELECT DISTINCT m.previewUrl FROM Message m WHERE m.fileUrl = :fileUrl " +
           "AND m.encryptedContent IS NULL AND m.previewUrl IS NOT NULL")
    List<String> findPreviewUrlsForFileUrl(@Param("fileUrl") String fileUrl);

    /** 同一个原图（转发副本）共用一套预览图：明文引用一起换成新生成的。 */
    @Modifying
    @Query("UPDATE Message m SET m.thumbnailUrl = :thumbnailUrl, m.previewUrl = :previewUrl, " +
           "m.renditionVersion = :version " +
           "WHERE m.fileUrl = :fileUrl AND m.encryptedContent IS NULL")
    int setRenditionsForFileUrl(@Param("fileUrl") String fileUrl,
                                @Param("thumbnailUrl") String thumbnailUrl,
                                @Param("previewUrl") String previewUrl,
                                @Param("version") Integer version);

    /** 这张原图做不出新预览图：保留现有的（有的话），只记上版本，下次不再尝试。 */
    @Modifying
    @Query("UPDATE Message m SET m.renditionVersion = :version " +
           "WHERE m.fileUrl = :fileUrl AND m.encryptedContent IS NULL")
    int markRenditionVersionForFileUrl(@Param("fileUrl") String fileUrl, @Param("version") Integer version);

    /**
     * 找出引用该文件、且 userId 所在聊天室里的未删除消息（按 id 升序）。转发和贴纸会让多条消息
     * 共用同一个 fileUrl，所以不能只看第一条消息所在的房间。调用方传 PageRequest.of(0, 1) 取一条用于审计。
     */
    @Query("SELECT m FROM Message m WHERE m.isDeleted = false " +
           "AND (m.fileUrl = :fileUrl OR m.imageGenUrl = :fileUrl " +
           "     OR m.thumbnailUrl = :fileUrl OR m.previewUrl = :fileUrl) " +
           "AND EXISTS (SELECT 1 FROM ChatRoomMember crm " +
           "            WHERE crm.chatRoom.id = m.chatRoom.id AND crm.user.id = :userId) " +
           "ORDER BY m.id ASC")
    List<Message> findActiveMessagesReferencingFileUrlVisibleTo(@Param("fileUrl") String fileUrl,
                                                                @Param("userId") Long userId,
                                                                Pageable pageable);
}
