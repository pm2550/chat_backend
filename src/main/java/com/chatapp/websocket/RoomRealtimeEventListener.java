package com.chatapp.websocket;

import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.service.RoomRealtimeEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 把会话资料 / 成员变化推给在线用户。只在事务提交后执行（没有事务时直接执行）。
 */
@Component
@RequiredArgsConstructor
public class RoomRealtimeEventListener {

    private final RawWebSocketHandler webSocketHandler;
    private final ChatRoomRepository chatRoomRepository;

    @TransactionalEventListener(fallbackExecution = true)
    public void onRoomUpdated(RoomRealtimeEvents.RoomUpdated event) {
        broadcastRoom(event.chatRoomId());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMembersAdded(RoomRealtimeEvents.MembersAdded event) {
        event.userIds().forEach(userId ->
                webSocketHandler.sendRoomMembershipChanged(userId, event.chatRoomId(), true, null));
        broadcastRoom(event.chatRoomId());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMembersRemoved(RoomRealtimeEvents.MembersRemoved event) {
        String reason = event.reason().wireName();
        event.userIds().forEach(userId ->
                webSocketHandler.sendRoomMembershipChanged(userId, event.chatRoomId(), false, reason));
        if (event.reason() != RoomRealtimeEvents.Reason.DELETED) {
            // 剩下的成员需要更新人数。
            broadcastRoom(event.chatRoomId());
        }
    }

    private void broadcastRoom(Long chatRoomId) {
        chatRoomRepository.findById(chatRoomId).ifPresent(webSocketHandler::broadcastChatRoomUpdated);
    }
}
