package com.chatapp.repository;

import com.chatapp.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 用户Repository接口
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 根据用户名查找用户
     */
    Optional<User> findByUsername(String username);

    /**
     * 查找第一个拥有指定角色的用户。
     */
    Optional<User> findFirstByRolesContainingOrderByIdAsc(User.Role role);

    /**
     * 根据邮箱查找用户
     */
    Optional<User> findByEmail(String email);

    /**
     * 根据用户名或邮箱查找用户
     */
    Optional<User> findByUsernameOrEmail(String username, String email);

    /**
     * 检查用户名是否存在
     */
    boolean existsByUsername(String username);

    /**
     * 检查邮箱是否存在
     */
    boolean existsByEmail(String email);

    /**
     * 查找激活的用户
     */
    List<User> findByIsActiveTrue();

    /**
     * 根据在线状态查找用户
     */
    List<User> findByOnlineStatus(User.OnlineStatus onlineStatus);

    /**
     * 搜索用户（按用户名、显示名模糊匹配；邮箱只做完整匹配）。
     * 邮箱不做模糊匹配：否则可以一个字一个字地试出别人的邮箱。
     */
    @Query("SELECT u FROM User u WHERE u.isActive = true AND " +
           "(LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.displayName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) = LOWER(:keyword))")
    Page<User> searchUsers(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 查找最近活跃的用户
     */
    @Query("SELECT u FROM User u WHERE u.isActive = true AND u.lastSeen > :since ORDER BY u.lastSeen DESC")
    List<User> findRecentlyActiveUsers(@Param("since") LocalDateTime since);

    /**
     * 根据ID列表查找用户
     */
    List<User> findByIdIn(List<Long> userIds);

    /**
     * 根据用户名列表查找用户。
     */
    List<User> findByUsernameIn(List<String> usernames);

    /**
     * 统计在线用户数量
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.onlineStatus = 'ONLINE'")
    long countOnlineUsers();

    @Query("SELECT u.presenceStatus FROM User u WHERE u.id = :userId")
    Optional<User.OnlineStatus> findPresenceStatus(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE User u SET u.onlineStatus = :status WHERE u.id = :userId")
    int updateOnlineStatusOnly(@Param("userId") Long userId, @Param("status") User.OnlineStatus status);

    /** 下线：显示离线并记下最后在线时间。隐身（选了离线）的人不更新时间，否则等于暴露了他的活动。 */
    @Modifying
    @Query("UPDATE User u SET u.onlineStatus = :offline, " +
           "u.lastSeen = CASE WHEN u.presenceStatus = :offline THEN u.lastSeen ELSE :now END " +
           "WHERE u.id = :userId")
    int markOffline(@Param("userId") Long userId,
                    @Param("offline") User.OnlineStatus offline,
                    @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE User u SET u.onlineStatus = :offline WHERE u.onlineStatus IS NULL OR u.onlineStatus <> :offline")
    int markAllOffline(@Param("offline") User.OnlineStatus offline);
}
