package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.BotDto;
import com.chatapp.dto.UserDto;
import com.chatapp.service.BotService;
import com.chatapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/bots")
@RequiredArgsConstructor
public class BotController {

    private final BotService botService;
    private final UserService userService;

    @PostMapping
    public ResponseEntity<ApiResponse<BotDto>> createBot(
            @Valid @RequestBody BotDto.CreateRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        BotDto result = botService.createBot(currentUser.getId(), request);
        return ResponseEntity.ok(ApiResponse.success("机器人创建成功", result));
    }

    @PutMapping("/{botId}")
    public ResponseEntity<ApiResponse<BotDto>> updateBot(
            @PathVariable Long botId,
            @RequestBody BotDto.UpdateRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        BotDto result = botService.updateBot(botId, currentUser.getId(), request);
        return ResponseEntity.ok(ApiResponse.success("机器人更新成功", result));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<BotDto>>> getMyBots(Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        List<BotDto> result = botService.getMyBots(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{botId}")
    public ResponseEntity<ApiResponse<BotDto>> getBot(
            @PathVariable Long botId,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        BotDto result = botService.getBot(botId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{botId}/character-card/import")
    public ResponseEntity<ApiResponse<BotDto>> importCharacterCard(
            @PathVariable Long botId,
            @Valid @RequestBody BotDto.CharacterCardImportRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        BotDto result = botService.importCharacterCard(botId, currentUser.getId(), request.getCard());
        return ResponseEntity.ok(ApiResponse.success("角色卡已导入", result));
    }

    @GetMapping("/{botId}/character-card/export")
    public ResponseEntity<ApiResponse<Map<String, Object>>> exportCharacterCard(
            @PathVariable Long botId,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        Map<String, Object> result = botService.exportCharacterCard(botId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @DeleteMapping("/{botId}")
    public ResponseEntity<ApiResponse<Void>> deleteBot(
            @PathVariable Long botId,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        botService.deleteBot(botId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.<Void>success("机器人已删除", null));
    }

    @PostMapping("/chat-rooms/{roomId}/bots/{botId}/add")
    public ResponseEntity<ApiResponse<Void>> addBotToChatRoom(
            @PathVariable Long roomId,
            @PathVariable Long botId,
            @RequestBody(required = false) BotDto.AddToChatRoomRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        botService.addBotToChatRoom(roomId, botId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.<Void>success("机器人已添加到聊天室", null));
    }

    @DeleteMapping("/chat-rooms/{roomId}/bots/{botId}")
    public ResponseEntity<ApiResponse<Void>> removeBotFromChatRoom(
            @PathVariable Long roomId,
            @PathVariable Long botId,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        botService.removeBotFromChatRoom(roomId, botId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.<Void>success("机器人已从聊天室移除", null));
    }

    @PutMapping("/chat-rooms/{roomId}/bots/{botId}")
    public ResponseEntity<ApiResponse<BotDto>> updateRoomBotConfig(
            @PathVariable Long roomId,
            @PathVariable Long botId,
            @RequestBody BotDto.AddToChatRoomRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        BotDto result = botService.updateRoomBotConfig(roomId, botId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success("聊天室机器人配置已更新", result));
    }

    @GetMapping("/chat-rooms/{roomId}/bots")
    public ResponseEntity<ApiResponse<List<BotDto>>> getBotsInChatRoom(@PathVariable Long roomId) {
        List<BotDto> result = botService.getBotsInChatRoom(roomId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
