package com.chatapp.repository;

import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.ChatRoomMember;
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

    @Query("SELECT CASE WHEN crm.isMuted = true THEN true ELSE false END FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    boolean isMuted(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    void removeMember(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.isActive = true AND EXISTS (SELECT 1 FROM ChatRoomMember crm WHERE crm.chatRoom = cr AND crm.user.id = :userId)")
    Page<ChatRoom> findByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT crm FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId")
    List<ChatRoomMember> findMembersByRoomId(@Param("roomId") Long roomId);

    @Query("SELECT crm.user.id FROM ChatRoomMember crm WHERE crm.chatRoom.id = :roomId")
    List<Long> findMemberUserIdsByRoomId(@Param("roomId") Long roomId);

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.isPrivate = false AND cr.isActive = true AND " +
           "(LOWER(cr.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(cr.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ChatRoom> searchPublicRooms(@Param("keyword") String keyword, Pageable pageable);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.isAdmin = CASE WHEN crm.isAdmin = true THEN false ELSE true END, " +
           "crm.memberRole = CASE WHEN crm.memberRole = 'ADMIN' THEN 'MEMBER' ELSE 'ADMIN' END " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    void toggleAdminStatus(@Param("roomId") Long roomId, @Param("userId") Long userId);

    @Modifying
    @Query("UPDATE ChatRoomMember crm SET crm.isMuted = CASE WHEN crm.isMuted = true THEN false ELSE true END " +
           "WHERE crm.chatRoom.id = :roomId AND crm.user.id = :userId")
    void toggleMuteStatus(@Param("roomId") Long roomId, @Param("userId") Long userId);
}
