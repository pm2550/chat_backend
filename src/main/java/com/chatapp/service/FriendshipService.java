package com.chatapp.service;

import com.chatapp.entity.Friendship;
import com.chatapp.entity.User;
import com.chatapp.repository.FriendshipRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 好友关系服务类
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class FriendshipService {

    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;

    /**
     * 发送好友请求
     */
    public Friendship sendFriendRequest(Long userId, Long friendId) {
        // 检查参数有效性
        if (userId.equals(friendId)) {
            throw new IllegalArgumentException("不能添加自己为好友");
        }

        // 检查用户是否存在
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        User friend = userRepository.findById(friendId)
                .orElseThrow(() -> new RuntimeException("目标用户不存在"));

        // 检查是否已经是好友或已有请求
        if (friendshipRepository.areFriends(userId, friendId)) {
            throw new IllegalArgumentException("已经是好友关系");
        }

        if (friendshipRepository.hasPendingRequest(userId, friendId)) {
            throw new IllegalArgumentException("已发送好友请求，请等待对方回应");
        }

        // 检查对方是否已向自己发送请求
        if (friendshipRepository.hasPendingRequest(friendId, userId)) {
            // 直接接受对方的请求
            return acceptFriendRequest(friendId, userId);
        }

        // 创建新的好友请求
        Friendship friendship = new Friendship(user, friend);
        friendship = friendshipRepository.save(friendship);

        log.info("用户 {} 向用户 {} 发送好友请求", user.getUsername(), friend.getUsername());
        return friendship;
    }

    /**
     * 接受好友请求
     */
    public Friendship acceptFriendRequest(Long userId, Long friendId) {
        Friendship friendship = friendshipRepository.findFriendshipBetween(userId, friendId)
                .orElseThrow(() -> new RuntimeException("好友请求不存在"));

        // 检查请求状态
        if (friendship.getStatus() != Friendship.FriendshipStatus.PENDING) {
            throw new IllegalArgumentException("好友请求状态无效");
        }

        // 检查权限（只有接收方可以接受请求）
        if (!friendship.getFriend().getId().equals(userId)) {
            throw new IllegalArgumentException("只能接受发送给自己的好友请求");
        }

        // 接受请求
        friendship.accept();
        friendship = friendshipRepository.save(friendship);

        log.info("用户 {} 接受了用户 {} 的好友请求", 
                friendship.getFriend().getUsername(), 
                friendship.getUser().getUsername());
        return friendship;
    }

    /**
     * 拒绝好友请求
     */
    public void declineFriendRequest(Long userId, Long friendId) {
        Friendship friendship = friendshipRepository.findFriendshipBetween(userId, friendId)
                .orElseThrow(() -> new RuntimeException("好友请求不存在"));

        // 检查权限
        if (!friendship.getFriend().getId().equals(userId)) {
            throw new IllegalArgumentException("只能拒绝发送给自己的好友请求");
        }

        friendship.decline();
        friendshipRepository.save(friendship);

        log.info("用户 {} 拒绝了用户 {} 的好友请求", 
                friendship.getFriend().getUsername(), 
                friendship.getUser().getUsername());
    }

    /**
     * 删除好友
     */
    public void removeFriend(Long userId, Long friendId) {
        Friendship friendship = friendshipRepository.findFriendshipBetween(userId, friendId)
                .orElseThrow(() -> new RuntimeException("好友关系不存在"));

        if (!friendshipRepository.areFriends(userId, friendId)) {
            throw new IllegalArgumentException("不是好友关系");
        }

        friendshipRepository.delete(friendship);

        log.info("用户 {} 删除了好友 {}", userId, friendId);
    }

    /**
     * 屏蔽用户
     */
    public void blockUser(Long userId, Long targetId) {
        Friendship friendship = friendshipRepository.findFriendshipBetween(userId, targetId)
                .orElse(null);

        if (friendship == null) {
            // 如果没有好友关系，创建一个屏蔽关系
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("用户不存在"));
            User target = userRepository.findById(targetId)
                    .orElseThrow(() -> new RuntimeException("目标用户不存在"));
            
            friendship = new Friendship(user, target);
        }

        friendship.block();
        friendshipRepository.save(friendship);

        log.info("用户 {} 屏蔽了用户 {}", userId, targetId);
    }

    /**
     * 取消屏蔽用户
     */
    public void unblockUser(Long userId, Long targetId) {
        Friendship friendship = friendshipRepository.findFriendshipBetween(userId, targetId)
                .orElseThrow(() -> new RuntimeException("屏蔽关系不存在"));

        if (!friendship.getIsBlocked()) {
            throw new IllegalArgumentException("用户未被屏蔽");
        }

        friendship.setIsBlocked(false);
        friendship.setStatus(Friendship.FriendshipStatus.DECLINED);
        friendshipRepository.save(friendship);

        log.info("用户 {} 取消屏蔽用户 {}", userId, targetId);
    }

    /**
     * 设置好友备注名
     */
    public void setFriendAlias(Long userId, Long friendId, String alias) {
        Friendship friendship = friendshipRepository.findFriendshipBetween(userId, friendId)
                .orElseThrow(() -> new RuntimeException("好友关系不存在"));

        if (!friendshipRepository.areFriends(userId, friendId)) {
            throw new IllegalArgumentException("不是好友关系");
        }

        friendship.setFriendAlias(alias);
        friendshipRepository.save(friendship);
    }

    /**
     * 置顶/取消置顶好友
     */
    public void togglePinFriend(Long userId, Long friendId) {
        Friendship friendship = friendshipRepository.findFriendshipBetween(userId, friendId)
                .orElseThrow(() -> new RuntimeException("好友关系不存在"));

        if (!friendshipRepository.areFriends(userId, friendId)) {
            throw new IllegalArgumentException("不是好友关系");
        }

        friendship.setIsPinned(!friendship.getIsPinned());
        friendshipRepository.save(friendship);
    }

    /**
     * 获取好友列表
     */
    public List<User> getFriends(Long userId) {
        List<Friendship> friendships = friendshipRepository.findAcceptedFriendsByUserId(userId);
        
        return friendships.stream()
                .map(friendship -> {
                    // 返回对方用户
                    return friendship.getUser().getId().equals(userId) 
                            ? friendship.getFriend() 
                            : friendship.getUser();
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取收到的好友请求
     */
    public List<Friendship> getPendingFriendRequests(Long userId) {
        return friendshipRepository.findPendingRequestsForUserId(userId);
    }

    /**
     * 获取发送的好友请求
     */
    public List<Friendship> getSentFriendRequests(Long userId) {
        return friendshipRepository.findPendingRequestsByUserId(userId);
    }

    /**
     * 检查是否为好友
     */
    public boolean areFriends(Long userId, Long friendId) {
        return friendshipRepository.areFriends(userId, friendId);
    }

    /**
     * 获取好友数量
     */
    public Long getFriendCount(Long userId) {
        return friendshipRepository.countFriendsByUserId(userId);
    }

    /**
     * 搜索好友
     */
    public List<User> searchFriends(Long userId, String keyword) {
        List<Friendship> friendships = friendshipRepository.findAcceptedFriendsByUserId(userId);
        
        return friendships.stream()
                .map(friendship -> {
                    User friend = friendship.getUser().getId().equals(userId) 
                            ? friendship.getFriend() 
                            : friendship.getUser();
                    return friend;
                })
                .filter(friend -> {
                    String alias = friendships.stream()
                        .filter(f -> (f.getUser().getId().equals(userId) ? f.getFriend() : f.getUser()).getId().equals(friend.getId()))
                        .findFirst()
                        .map(Friendship::getFriendAlias)
                        .orElse(null);
                    
                    return friend.getDisplayName().toLowerCase().contains(keyword.toLowerCase()) ||
                           friend.getUsername().toLowerCase().contains(keyword.toLowerCase()) ||
                           (alias != null && alias.toLowerCase().contains(keyword.toLowerCase()));
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取置顶好友
     */
    public List<User> getPinnedFriends(Long userId) {
        List<Friendship> friendships = friendshipRepository.findPinnedFriendsByUserId(userId);
        
        return friendships.stream()
                .map(friendship -> {
                    return friendship.getUser().getId().equals(userId) 
                            ? friendship.getFriend() 
                            : friendship.getUser();
                })
                .collect(Collectors.toList());
    }
} 