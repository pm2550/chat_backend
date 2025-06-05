package com.chatapp.repository;

import com.chatapp.entity.ChatRoom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 聊天室Repository接口
 */
@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    /**
     * 查找激活的聊天室
     */
    List<ChatRoom> findByIsActiveTrueOrderByUpdatedAtDesc();

    /**
     * 根据房间类型查找聊天室
     */
    List<ChatRoom> findByRoomTypeAndIsActiveTrueOrderByUpdatedAtDesc(ChatRoom.RoomType roomType);

    /**
     * 根据创建者查找聊天室
     */
    List<ChatRoom> findByCreatedByIdAndIsActiveTrueOrderByCreatedAtDesc(Long createdById);

    /**
     * 搜索聊天室（按名称或描述）
     */
    @Query("SELECT cr FROM ChatRoom cr WHERE cr.isActive = true AND " +
           "(LOWER(cr.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(cr.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ChatRoom> searchChatRooms(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 查找用户参与的聊天室
     */
    @Query("SELECT DISTINCT crm.chatRoom FROM ChatRoomMember crm WHERE crm.user.id = :userId AND crm.chatRoom.isActive = true ORDER BY crm.chatRoom.updatedAt DESC")
    List<ChatRoom> findChatRoomsByUserId(@Param("userId") Long userId);

    /**
     * 查找两个用户之间的私聊室
     */
    @Query("SELECT cr FROM ChatRoom cr WHERE cr.roomType = 'PRIVATE' AND cr.isActive = true AND " +
           "EXISTS (SELECT 1 FROM ChatRoomMember crm1 WHERE crm1.chatRoom = cr AND crm1.user.id = :userId1) AND " +
           "EXISTS (SELECT 1 FROM ChatRoomMember crm2 WHERE crm2.chatRoom = cr AND crm2.user.id = :userId2)")
    Optional<ChatRoom> findPrivateChatRoom(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    /**
     * 查找公开的聊天室
     */
    @Query("SELECT cr FROM ChatRoom cr WHERE cr.isPrivate = false AND cr.isActive = true ORDER BY cr.updatedAt DESC")
    Page<ChatRoom> findPublicChatRooms(Pageable pageable);

    /**
     * 统计聊天室成员数量
     */
    @Query("SELECT COUNT(crm) FROM ChatRoomMember crm WHERE crm.chatRoom.id = :chatRoomId")
    long countChatRoomMembers(@Param("chatRoomId") Long chatRoomId);

    /**
     * 检查用户是否是聊天室成员
     */
    @Query("SELECT COUNT(crm) > 0 FROM ChatRoomMember crm WHERE crm.chatRoom.id = :chatRoomId AND crm.user.id = :userId")
    boolean isUserMemberOfChatRoom(@Param("chatRoomId") Long chatRoomId, @Param("userId") Long userId);

    /**
     * 查找热门聊天室（按成员数量排序）
     */
    @Query("SELECT cr FROM ChatRoom cr LEFT JOIN cr.members m WHERE cr.isActive = true AND cr.isPrivate = false " +
           "GROUP BY cr ORDER BY COUNT(m) DESC")
    Page<ChatRoom> findPopularChatRooms(Pageable pageable);
} 