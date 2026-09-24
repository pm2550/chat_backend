package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.PollDto;
import com.chatapp.dto.UserDto;
import com.chatapp.service.MessageService;
import com.chatapp.service.PollService;
import com.chatapp.service.UserService;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/polls")
@RequiredArgsConstructor
public class PollController {

    private final PollService pollService;
    private final MessageService messageService;
    private final UserService userService;
    private final RawWebSocketHandler webSocketHandler;

    @PostMapping
    public ResponseEntity<ApiResponse<PollDto>> create(@RequestBody PollDto.CreateRequest request,
                                                       Authentication auth) {
        UserDto user = userService.findByUsername(auth.getName());
        PollDto poll = pollService.create(user.getId(), request);
        // 投票本身是一条 POLL 消息：和普通消息一样实时推给房间（含发起人的其他设备）并发离线通知，
        // 否则别人要重进房间才看得到。消息里带 pollId，客户端据此渲染投票卡。
        webSocketHandler.broadcastMessage(messageService.getMessageForBroadcast(poll.getMessageId()));
        return ResponseEntity.ok(ApiResponse.success("投票已创建", poll));
    }

    @PostMapping("/{pollId}/votes")
    public ResponseEntity<ApiResponse<PollDto>> vote(@PathVariable Long pollId,
                                                     @RequestBody PollDto.VoteRequest request,
                                                     Authentication auth) {
        UserDto user = userService.findByUsername(auth.getName());
        PollDto poll = pollService.vote(
                pollId,
                user.getId(),
                request.getOptionIndexes());
        Long roomId = pollService.getChatRoomId(pollId);
        if (roomId != null) {
            webSocketHandler.broadcastPollVoted(roomId, poll);
        }
        return ResponseEntity.ok(ApiResponse.success(poll));
    }

    @DeleteMapping("/{pollId}/votes")
    public ResponseEntity<ApiResponse<Void>> deleteVote(@PathVariable Long pollId, Authentication auth) {
        UserDto user = userService.findByUsername(auth.getName());
        pollService.deleteVote(pollId, user.getId());
        Long roomId = pollService.getChatRoomId(pollId);
        if (roomId != null) {
            webSocketHandler.broadcastPollVoted(roomId, pollService.get(pollId, user.getId()));
        }
        return ResponseEntity.ok(ApiResponse.success("投票已撤销", null));
    }

    @GetMapping("/{pollId}")
    public ResponseEntity<ApiResponse<PollDto>> get(@PathVariable Long pollId, Authentication auth) {
        UserDto user = userService.findByUsername(auth.getName());
        return ResponseEntity.ok(ApiResponse.success(pollService.get(pollId, user.getId())));
    }
}
