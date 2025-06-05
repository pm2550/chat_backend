package com.chatapp.repository;

import com.chatapp.entity.User;
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
 * 用户Repository接口
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 根据用户名查找用户
     */
    Optional<User> findByUsername(String username);

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
     * 搜索用户（按用户名、显示名或邮箱）
     */
    @Query("SELECT u FROM User u WHERE u.isActive = true AND " +
           "(LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.displayName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')))")
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
     * 统计在线用户数量
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.onlineStatus = 'ONLINE'")
    long countOnlineUsers();
} 