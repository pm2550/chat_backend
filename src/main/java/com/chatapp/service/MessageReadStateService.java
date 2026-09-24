package com.chatapp.service;

import com.chatapp.dto.MessageDto;
import com.chatapp.dto.ReadReceiptDto;
import com.chatapp.entity.Message;
import com.chatapp.entity.MessageReadReceipt;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageReadReceiptRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 已读数的唯一定义：一条消息的已读数 = 除发送者外、已读位置
 * （chat_room_members.last_read_message_id）不早于这条消息的当前成员人数。
 * 关了"已读回执"的成员既不计数，也不出现在已读名单里；已读名单和已读数永远是同一批人。
 *
 * <p>整房间已读和逐条已读都只推进成员自己的已读位置，不再往消息上累加计数——以前两条路径
 * 各加各的：同一个人进群时会被算两次，而整房间已读在群里又最多只加到 1。
 * 这里按需算：一页消息只查一次这些房间成员的已读位置，没有 N+1。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageReadStateService {

    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final MessageReadReceiptRepository readReceiptRepository;
    private final UserPrivacyService userPrivacyService;

    /**
     * 按上面的定义给 DTO 填已读数和已读状态（READ = 至少一人读过）。dtos 由 messages 转换而来，按 id 对应。
     */
    public List<MessageDto> applyReadState(List<Message> messages, List<MessageDto> dtos) {
        if (messages == null || messages.isEmpty() || dtos == null || dtos.isEmpty()) {
            return dtos;
        }
        Map<Long, Message> byId = new HashMap<>();
        Set<Long> roomIds = new HashSet<>();
        for (Message message : messages) {
            if (message == null || message.getId() == null || message.getChatRoom() == null) {
                continue;
            }
            byId.put(message.getId(), message);
            roomIds.add(message.getChatRoom().getId());
        }
        Map<Long, List<ReadMark>> marksByRoom = visibleReadMarks(roomIds).stream()
                .collect(Collectors.groupingBy(ReadMark::roomId));
        for (MessageDto dto : dtos) {
            Message message = dto == null ? null : byId.get(dto.getId());
            if (message == null) {
                continue;
            }
            int readers = (int) marksByRoom.getOrDefault(message.getChatRoom().getId(), List.of()).stream()
                    .filter(mark -> hasRead(mark, message))
                    .count();
            dto.setReadCount(readers);
            dto.setMessageStatus(statusFor(dto.getMessageStatus(), readers));
        }
        return dtos;
    }

    /** 已读名单：与 {@link #applyReadState} 的计数是同一批人。有逐条已读记录的带上读到的时间，先读的在前。 */
    public List<ReadReceiptDto> readers(Message message) {
        if (message == null || message.getId() == null || message.getChatRoom() == null) {
            return List.of();
        }
        List<Long> readerIds = visibleReadMarks(List.of(message.getChatRoom().getId())).stream()
                .filter(mark -> hasRead(mark, message))
                .map(ReadMark::userId)
                .toList();
        if (readerIds.isEmpty()) {
            return List.of();
        }
        Map<Long, LocalDateTime> readAt = readReceiptRepository.findByMessageIdOrderByReadAtAsc(message.getId())
                .stream()
                .filter(receipt -> receipt.getUser() != null && receipt.getReadAt() != null)
                .collect(Collectors.toMap(receipt -> receipt.getUser().getId(),
                        MessageReadReceipt::getReadAt, (first, second) -> first));
        Map<Long, User> users = userRepository.findAllById(readerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<ReadReceiptDto> result = new ArrayList<>();
        for (Long userId : readerIds) {
            User user = users.get(userId);
            if (user != null) {
                result.add(new ReadReceiptDto(user.getId(), user.getUsername(), user.getDisplayName(),
                        user.getAvatarUrl(), readAt.get(userId)));
            }
        }
        result.sort(Comparator.comparing(ReadReceiptDto::getReadAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ReadReceiptDto::getUserId));
        return result;
    }

    private List<ReadMark> visibleReadMarks(Collection<Long> roomIds) {
        if (roomIds.isEmpty()) {
            return List.of();
        }
        List<ReadMark> marks = chatRoomRepository.findReadMarksByRoomIds(roomIds).stream()
                .filter(mark -> mark.getUserId() != null && mark.getLastReadMessageId() != null)
                .map(mark -> new ReadMark(mark.getRoomId(), mark.getUserId(), mark.getLastReadMessageId()))
                .toList();
        if (marks.isEmpty()) {
            return marks;
        }
        // 关了已读回执的人（包括之后才关的）不算已读。
        Set<Long> hidden = userPrivacyService.usersWithReadReceiptsDisabled(
                marks.stream().map(ReadMark::userId).distinct().toList());
        return hidden.isEmpty()
                ? marks
                : marks.stream().filter(mark -> !hidden.contains(mark.userId())).toList();
    }

    private static boolean hasRead(ReadMark mark, Message message) {
        Long senderId = message.getSender() == null ? null : message.getSender().getId();
        return !Objects.equals(mark.userId(), senderId) && mark.lastReadMessageId() >= message.getId();
    }

    /** 发送中/失败的状态保持原样；否则有人读过就是已读，没人读过就退回"已送达"。 */
    private static Message.MessageStatus statusFor(Message.MessageStatus current, int readers) {
        if (current == Message.MessageStatus.SENDING || current == Message.MessageStatus.FAILED) {
            return current;
        }
        if (readers > 0) {
            return Message.MessageStatus.READ;
        }
        return current == Message.MessageStatus.READ ? Message.MessageStatus.DELIVERED : current;
    }

    private record ReadMark(Long roomId, Long userId, Long lastReadMessageId) {
    }
}
