package com.chatapp.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 好友关系实体类
 */
@Entity
@Table(name = "friendships", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "friend_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Friendship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 发起好友请求的用户
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 被添加为好友的用户
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "friend_id", nullable = false)
    private User friend;

    // 好友状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FriendshipStatus status = FriendshipStatus.PENDING;

    // 好友备注名
    @Column(name = "friend_alias", length = 100)
    private String friendAlias;

    // 是否被屏蔽
    @Column(name = "is_blocked")
    private Boolean isBlocked = false;

    // 是否置顶
    @Column(name = "is_pinned")
    private Boolean isPinned = false;

    // 创建时间
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // 更新时间
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // 接受好友请求的时间
    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    /**
     * 好友状态枚举
     */
    public enum FriendshipStatus {
        PENDING("待接受"),
        ACCEPTED("已接受"),
        DECLINED("已拒绝"),
        BLOCKED("已屏蔽");

        private final String description;

        FriendshipStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 构造方法 - 创建好友请求
     */
    public Friendship(User user, User friend) {
        this.user = user;
        this.friend = friend;
        this.status = FriendshipStatus.PENDING;
        this.isBlocked = false;
        this.isPinned = false;
    }

    /**
     * 接受好友请求
     */
    public void accept() {
        this.status = FriendshipStatus.ACCEPTED;
        this.acceptedAt = LocalDateTime.now();
    }

    /**
     * 拒绝好友请求
     */
    public void decline() {
        this.status = FriendshipStatus.DECLINED;
    }

    /**
     * 屏蔽好友
     */
    public void block() {
        this.status = FriendshipStatus.BLOCKED;
        this.isBlocked = true;
    }

    /**
     * 判断是否为互相好友
     */
    public boolean isMutualFriend() {
        return status == FriendshipStatus.ACCEPTED;
    }
} 