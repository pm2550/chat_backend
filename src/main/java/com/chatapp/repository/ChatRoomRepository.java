package com.chatapp.repository;

import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
import com.chatapp.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    List<ChatRoom> findByIsActiveTrueOrderByUpdatedAtDesc();

    List<ChatRoom> findByRoomTypeAndIsActiveTrueOrderByUpdatedAtDesc(ChatRoom.RoomType roomType);

    List<ChatRoom> findByCreatedByIdAndIsActiveTrueOrderByCreatedAtDesc(Long createdById);

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.isActive = true AND " +
           "(LOWER(cr.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(cr.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ChatRoom> searchChatRooms(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT DISTINCT crm.chatRoom FROM ChatRoomMember crm WHERE crm.user.id = :userId AND crm.chatRoom.isActive = true ORDER BY crm.chatRoom.updatedAt DESC")
    List<ChatRoom> findChatRoomsByUserId(@Param("userId") Long userId);

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.roomType = 'PRIVATE' AND cr.isActive = true AND " +
           "EXISTS (SELECT 1 FROM ChatRoomMember crm1 WHERE crm1.chatRoom = cr AND crm1.user.id = :userId1) AND " +
           "EXISTS (SELECT 1 FROM ChatRoomMember crm2 WHERE crm2.chatRoom = cr AND crm2.user.id = :userId2)")
    Optional<ChatRoom> findPrivateChatRoom(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.isPrivate = false AND cr.isActive = true ORDER BY cr.updatedAt DESC")
    Page<ChatRoom> findPublicChatRooms(Pageable pageable);

    @Query("SELECT COUNT(crm) FROM ChatRoomMember crm WHERE crm.chatRoom.id = :chatRoomId")
    long countChatRoomMembers(@Param("chatRoomId") Long chatRoomId);

    @Query("SELECT COUNT(crm) > 0 FROM ChatRoomMember crm WHERE crm.chatRoom.id = :chatRoomId AND crm.user.id = :userId")
    boolean isUserMemberOfChatRoom(@Param("chatRoomId") Long chatRoomId, @Param("userId") Long userId);

    @Query("SELECT cr FROM ChatRoom cr LEFT JOIN cr.members m WHERE cr.isActive = true AND cr.isPrivate = false " +
           "GROUP BY cr ORDER BY COUNT(m) DESC")
    Page<ChatRoom> findPopularChatRooms(Pageable pageable);

    // --- Methods required by services ---

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.roomType = 'PRIVATE' AND cr.isActive = true AND " +
           "EXISTS (SELECT 1 FROM ChatRoomMember crm1 WHERE crm1.chatRoom = cr AND crm1.user.id = :userId1) AND " +
           "EXISTS (SELECT 1 FROM ChatRoomMember crm2 WHERE crm2.chatRoom = cr AND crm2.user.id = :userId2)")
    Optional<ChatRoom> findPrivateChatBetween(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    @Query("SELECT COUNT(crm) > 0 FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    boolean isMember(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Query("SELECT COUNT(crm) > 0 FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId AND (crm.isAdmin = true OR crm.memberRole = 'ADMIN' OR crm.memberRole = 'OWNER')")
    boolean isAdmin(@Param("roomId") Long roomId, @Param("userId") Long userId);

    // Item 5: the send-block gate reads ONLY the moderation mute, never the
    // user's own notification mute. Renamed from isMuted to isBotMuted.
    @Query("SELECT CASE WHEN crm.isBotMuted = true THEN true ELSE false END FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    boolean isBotMuted(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    void removeMember(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Query("SELECT cr FROM ChatRoomMember crm JOIN crm.chatRoom cr " +
           "WHERE crm.user.id = :userId AND cr.isActive = true " +
           "AND COALESCE(crm.isBlocked, false) = false " +
           "AND crm.hiddenAt IS NULL " +
           "ORDER BY COALESCE(crm.isPinned, false) DESC, cr.updatedAt DESC")
    Page<ChatRoom> findByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT cr FROM ChatRoomMember crm JOIN crm.chatRoom cr " +
           "WHERE crm.user.id = :userId AND cr.isActive = true " +
           "AND (:includeBlocked = true OR COALESCE(crm.isBlocked, false) = false) " +
           "AND (:includeHidden = true OR crm.hiddenAt IS NULL) " +
           "AND (:roomType IS NULL OR cr.roomType = :roomType) " +
           "ORDER BY COALESCE(crm.isPinned, false) DESC, cr.updatedAt DESC")
    Page<ChatRoom> findByUserIdWithDisplayState(@Param("userId") Long userId,
                                                @Param("includeHidden") boolean includeHidden,
                                                @Param("includeBlocked") boolean includeBlocked,
                                                @Param("roomType") ChatRoom.RoomType roomType,
                                                Pageable pageable);

    @Query("SELECT crm FROM ChatRoomMember crm WHERE crm.user.id = :userId " +
           "AND crm.chatRoom.id IN :roomIds")
    List<ChatRoomMember> findMembershipsByUserIdAndRoomIds(@Param("userId") Long userId,
                                                           @Param("roomIds") List<Long> roomIds);

    interface RoomMemberCountProjection {
        Long getRoomId();
        Long getMemberCount();
    }

    @Query("SELECT crm.chatRoom.id AS roomId, COUNT(crm.id) AS memberCount " +
           "FROM ChatRoomMember crm WHERE crm.chatRoom.id IN :roomIds GROUP BY crm.chatRoom.id")
    List<RoomMemberCountProjection> countMembersByRoomIds(@Param("roomIds") List<Long> roomIds);

    interface PrivateRoomParticipantProjection {
        Long getRoomId();
        Long getUserId();
        String getUsername();
        String getDisplayName();
        String getAvatarUrl();
        String getTitle();
        String getTitleColor();
        String getTitleEffect();
        User.OnlineStatus getOnlineStatus();
        java.time.LocalDateTime getLastSeen();
        Boolean getActive();
        java.time.LocalDateTime getCreatedAt();
        java.time.LocalDateTime getUpdatedAt();
    }

    @Query("SELECT crm.chatRoom.id AS roomId, u.id AS userId, u.username AS username, " +
           "u.displayName AS displayName, u.avatarUrl AS avatarUrl, u.title AS title, " +
           "u.titleColor AS titleColor, u.titleEffect AS titleEffect, " +
           "u.onlineStatus AS onlineStatus, u.lastSeen AS lastSeen, u.isActive AS active, " +
           "u.createdAt AS createdAt, u.updatedAt AS updatedAt " +
           "FROM ChatRoomMember crm JOIN crm.user u " +
           "WHERE crm.chatRoom.id IN :roomIds AND crm.chatRoom.roomType = 'PRIVATE'")
    List<PrivateRoomParticipantProjection> findPrivateParticipantsByRoomIds(
            @Param("roomIds") List<Long> roomIds);

    @Query("SELECT crm FROM ChatRoomMember crm JOIN FETCH crm.user WHERE crm.chatRoom.id = :roomId")
    List<ChatRoomMember> findMembersByRoomId(@Param("roomId") Long roomId);

    @Query("SELECT crm.user.id FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId")
    List<Long> findMemberUserIdsByRoomId(@Param("roomId") Long roomId);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.unreadCount = COALESCE(crm.unreadCount, 0) + 1, " +
           "crm.hiddenAt = NULL " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id <> :senderId " +
           "AND COALESCE(crm.isBlocked, false) = false")
    int incrementUnreadForRoomMembersExcept(@Param("roomId") Long roomId, @Param("senderId") Long senderId);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.hiddenAt = NULL " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId " +
           "AND COALESCE(crm.isBlocked, false) = false")
    int clearHiddenForMember(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.unreadCount = CASE WHEN COALESCE(crm.unreadCount, 0) > 0 THEN crm.unreadCount - 1 ELSE 0 END, " +
           "crm.lastReadMessageId = CASE WHEN crm.lastReadMessageId IS NULL OR crm.lastReadMessageId < :messageId THEN :messageId ELSE crm.lastReadMessageId END " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    int markMessageReadForMember(@Param("roomId") Long roomId,
                                 @Param("userId") Long userId,
                                 @Param("messageId") Long messageId);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.unreadCount = 0, crm.lastReadMessageId = :lastReadMessageId " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    int markRoomReadForMember(@Param("roomId") Long roomId,
                              @Param("userId") Long userId,
                              @Param("lastReadMessageId") Long lastReadMessageId);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.hiddenAt = :hiddenAt, crm.unreadCount = 0 " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    int hideRoomForMember(@Param("roomId") Long roomId,
                          @Param("userId") Long userId,
                          @Param("hiddenAt") java.time.LocalDateTime hiddenAt);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.hiddenAt = NULL, crm.isBlocked = false " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    int restoreRoomForMember(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.isBlocked = true, crm.hiddenAt = :blockedAt, crm.unreadCount = 0 " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    int blockRoomForMember(@Param("roomId") Long roomId,
                           @Param("userId") Long userId,
                           @Param("blockedAt") java.time.LocalDateTime blockedAt);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.isBlocked = false, crm.hiddenAt = NULL " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    int unblockRoomForMember(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.clearedBeforeMessageId = :messageId, crm.unreadCount = 0, crm.lastReadMessageId = :messageId " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    int updateClearedBeforeMessageId(@Param("roomId") Long roomId,
                                     @Param("userId") Long userId,
                                     @Param("messageId") Long messageId);

    @Query("SELECT COALESCE(crm.unreadCount, 0) FROM ChatRoomMember crm " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId " +
           "AND COALESCE(crm.isBlocked, false) = false")
    Optional<Integer> findUnreadCount(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Query("SELECT crm FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    Optional<ChatRoomMember> findMember(@Param("roomId") Long roomId, @Param("userId") Long userId);

    // Item 5: a user muting their OWN notifications writes only is_notification_muted.
    // It must NOT touch is_muted/is_bot_muted (that was the send-block bug).
    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.isNotificationMuted = :muted " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    int updateNotificationMuted(@Param("roomId") Long roomId,
                                @Param("userId") Long userId,
                                @Param("muted") boolean muted);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.isPinned = :pinned " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    int updatePinned(@Param("roomId") Long roomId,
                     @Param("userId") Long userId,
                     @Param("pinned") boolean pinned);

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.isPrivate = false AND cr.isActive = true AND " +
           "(LOWER(cr.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(cr.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ChatRoom> searchPublicRooms(@Param("keyword") String keyword, Pageable pageable);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.isAdmin = CASE WHEN crm.isAdmin = true THEN false ELSE true END, " +
           "crm.memberRole = CASE WHEN crm.memberRole = 'ADMIN' THEN 'MEMBER' ELSE 'ADMIN' END " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    void toggleAdminStatus(@Param("roomId") Long roomId, @Param("userId") Long userId);

    // Item 5: admin toggle (moderation mute). Dual-writes both the new is_bot_muted
    // and the legacy is_muted shadow. Both CASE branches read the SAME pre-update
    // is_muted value (SQL evaluates all RHS against the original row), so they flip together.
    @Modifying
    @Query("UPDATE ChatRoomMember crm SET " +
           "crm.isMuted = CASE WHEN crm.isMuted = true THEN false ELSE true END, " +
           "crm.isBotMuted = CASE WHEN crm.isMuted = true THEN false ELSE true END " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    void toggleMuteStatus(@Param("roomId") Long roomId, @Param("userId") Long userId);

    // --- F5: real OWNER role above admin ---

    @Query("SELECT COUNT(crm) > 0 FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId " +
           "AND crm.user.id = :userId AND crm.memberRole = 'OWNER'")
    boolean isOwner(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Query("SELECT COUNT(crm) FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId AND crm.memberRole = 'OWNER'")
    long countOwners(@Param("roomId") Long roomId);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.memberRole = :role, crm.isAdmin = :isAdmin " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    int assignMemberRole(@Param("roomId") Long roomId, @Param("userId") Long userId,
                         @Param("role") ChatRoomMember.MemberRole role, @Param("isAdmin") boolean isAdmin);

    // F5 Slice 2: explicitly set (not toggle) a member's moderation mute — used by bot moderation.
    // Item 5: dual-writes the new is_bot_muted and the legacy is_muted shadow.
    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.isBotMuted = :muted, crm.isMuted = :muted " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    int setMemberMuted(@Param("roomId") Long roomId, @Param("userId") Long userId, @Param("muted") boolean muted);
}
