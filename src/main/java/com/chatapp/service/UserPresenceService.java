package com.chatapp.service;

import com.chatapp.entity.User;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * users.online_status 的唯一写入口。它只反映"现在有没有前台连接"：
 * 第一条前台 WebSocket 连上时写成用户自己选的状态，最后一条断开时写成离线并记最后在线时间。
 * 登录不再改它——只登录、没打开连接的人不算在线（以前登录就标在线、不点退出永远在线）。
 *
 * <p>什么时候调用由 {@code RawWebSocketHandler} 按内存里的连接表决定，这里只负责落库。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UserPresenceService {

    private final UserRepository userRepository;

    /** 用户自己选的状态（在线/离开/忙碌/隐身），没选过就是在线。 */
    @Transactional(readOnly = true)
    public User.OnlineStatus chosenPresence(Long userId) {
        return userRepository.findPresenceStatus(userId).orElse(User.OnlineStatus.ONLINE);
    }

    /** 有前台连接：对外显示他选的状态（选了隐身就还是离线）。返回写入的状态。 */
    public User.OnlineStatus markConnected(Long userId) {
        User.OnlineStatus status = chosenPresence(userId);
        userRepository.updateOnlineStatusOnly(userId, status);
        return status;
    }

    /** 最后一条前台连接断开：离线，最后在线时间记为现在。 */
    public void markDisconnected(Long userId) {
        userRepository.markOffline(userId, User.OnlineStatus.OFFLINE, LocalDateTime.now());
    }

    /** 服务刚启动时一条连接都没有，库里残留的"在线"全部作废。 */
    public int resetAllOffline() {
        int updated = userRepository.markAllOffline(User.OnlineStatus.OFFLINE);
        if (updated > 0) {
            log.info("Presence reset on startup: {} users marked OFFLINE", updated);
        }
        return updated;
    }
}
