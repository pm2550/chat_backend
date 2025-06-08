package com.chatapp.repository;

import com.chatapp.entity.Friendship;
import com.chatapp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 好友关系Repository
 */
@Repository
public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    /**
     * 查找两个用户之间的好友关系
     */
    @Query("SELECT f FROM Friendship f WHERE " +
           "(f.user.id = :userId AND f.friend.id = :friendId) OR " +
           "(f.user.id = :friendId AND f.friend.id = :userId)")
    Optional<Friendship> findFriendshipBetween(@Param("userId") Long userId, @Param("friendId") Long friendId);

    /**
     * 查找用户的所有好友（已接受的好友关系）
     */
    @Query("SELECT f FROM Friendship f WHERE " +
           "(f.user.id = :userId OR f.friend.id = :userId) AND " +
           "f.status = 'ACCEPTED' AND f.isBlocked = false")
    List<Friendship> findAcceptedFriendsByUserId(@Param("userId") Long userId);

    /**
     * 查找用户发送的好友请求（待处理）
     */
    @Query("SELECT f FROM Friendship f WHERE f.user.id = :userId AND f.status = 'PENDING'")
    List<Friendship> findPendingRequestsByUserId(@Param("userId") Long userId);

    /**
     * 查找用户收到的好友请求（待处理）
     */
    @Query("SELECT f FROM Friendship f WHERE f.friend.id = :userId AND f.status = 'PENDING'")
    List<Friendship> findPendingRequestsForUserId(@Param("userId") Long userId);

    /**
     * 查找用户的所有好友关系（包括所有状态）
     */
    @Query("SELECT f FROM Friendship f WHERE f.user.id = :userId OR f.friend.id = :userId")
    List<Friendship> findAllFriendshipsByUserId(@Param("userId") Long userId);

    /**
     * 检查两个用户是否为好友
     */
    @Query("SELECT COUNT(f) > 0 FROM Friendship f WHERE " +
           "((f.user.id = :userId AND f.friend.id = :friendId) OR " +
           "(f.user.id = :friendId AND f.friend.id = :userId)) AND " +
           "f.status = 'ACCEPTED' AND f.isBlocked = false")
    boolean areFriends(@Param("userId") Long userId, @Param("friendId") Long friendId);

    /**
     * 检查是否存在好友请求
     */
    @Query("SELECT COUNT(f) > 0 FROM Friendship f WHERE " +
           "f.user.id = :userId AND f.friend.id = :friendId AND f.status = 'PENDING'")
    boolean hasPendingRequest(@Param("userId") Long userId, @Param("friendId") Long friendId);

    /**
     * 查找被屏蔽的用户
     */
    @Query("SELECT f FROM Friendship f WHERE " +
           "(f.user.id = :userId OR f.friend.id = :userId) AND " +
           "f.isBlocked = true")
    List<Friendship> findBlockedFriendsByUserId(@Param("userId") Long userId);

    /**
     * 统计用户的好友数量
     */
    @Query("SELECT COUNT(f) FROM Friendship f WHERE " +
           "(f.user.id = :userId OR f.friend.id = :userId) AND " +
           "f.status = 'ACCEPTED' AND f.isBlocked = false")
    Long countFriendsByUserId(@Param("userId") Long userId);

    /**
     * 查找置顶的好友
     */
    @Query("SELECT f FROM Friendship f WHERE " +
           "(f.user.id = :userId OR f.friend.id = :userId) AND " +
           "f.status = 'ACCEPTED' AND f.isBlocked = false AND f.isPinned = true")
    List<Friendship> findPinnedFriendsByUserId(@Param("userId") Long userId);

    /**
     * 根据备注名搜索好友
     */
    @Query("SELECT f FROM Friendship f WHERE " +
           "(f.user.id = :userId OR f.friend.id = :userId) AND " +
           "f.status = 'ACCEPTED' AND f.isBlocked = false AND " +
           "f.friendAlias LIKE %:alias%")
    List<Friendship> findFriendsByAlias(@Param("userId") Long userId, @Param("alias") String alias);
} 