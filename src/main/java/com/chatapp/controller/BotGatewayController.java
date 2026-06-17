package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.MessageDto;
import com.chatapp.dto.WorkspaceDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.entity.WorkspaceFile;
import com.chatapp.service.BotGatewayService;
import com.chatapp.service.BotRateLimitService;
import com.chatapp.service.BotTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inbound bot gateway (Phase 4 / F1) — the Telegram/Feishu-style "external service
 * posts AS a bot" surface. Authenticated by a per-bot token (NOT a user JWT); the
 * path is permitAll in SecurityConfig and the token is resolved here per request.
 */
@RestController
@RequestMapping("/api/bot-gateway/v1")
@RequiredArgsConstructor
public class BotGatewayController {

    private static final int INBOUND_PER_MINUTE = 60;

    private final BotTokenService botTokenService;
    private final BotGatewayService botGatewayService;
    private final BotRateLimitService rateLimitService;

    /** External bot posts a message into a bound room (Telegram sendMessage equivalent). */
    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<Map<String, Object>>> postMessage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_MESSAGE_SEND);
        Long roomId = parseRoomId(firstPresent(body, "roomId", "chatRoomId", "chat_room_id"));
        String content = body.get("content") != null ? body.get("content").toString() : null;
        Long replyToMessageId = parseOptionalLong(firstPresent(body, "replyToMessageId", "reply_to_message_id"));
        Long fileId = parseOptionalLong(firstPresent(body, "fileId", "file_id", "imageFileId", "image_file_id"));
        Message message = botGatewayService.postAsBot(bot, roomId, content, replyToMessageId, fileId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messageId", message.getId());
        if (message.getFileUrl() != null) {
            response.put("fileId", message.getId());
        }
        response.put("chatRoomId", roomId);
        response.put("status", "SENT");
        response.put("message", MessageDto.fromEntity(message));
        return ResponseEntity.ok(ApiResponse.success("sent", response));
    }

    /** Rooms this bot token is bound to (where it may post). */
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rooms(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_MESSAGE_SEND);
        return ResponseEntity.ok(ApiResponse.success(Map.<String, Object>of(
                "botId", bot.getId(),
                "rooms", botGatewayService.boundRoomIds(bot))));
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<Map<String, Object>>> roomMessages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_MESSAGE_READ);
        Page<Message> messages = botGatewayService.listMessages(
                bot,
                roomId,
                PageRequest.of(clamp(page, 0, 10_000), clamp(size, 1, 100), Sort.by("createdAt").descending()));
        return ResponseEntity.ok(ApiResponse.success(pageResponse(messages)));
    }

    @GetMapping("/rooms/{roomId}/search")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchMessages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long roomId,
            @RequestParam(required = false) String keyword,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_MESSAGE_READ);
        String searchTerm = keyword != null && !keyword.isBlank() ? keyword : query;
        if (searchTerm == null || searchTerm.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword or q required");
        }
        Page<Message> messages = botGatewayService.searchMessages(
                bot,
                roomId,
                searchTerm,
                PageRequest.of(clamp(page, 0, 10_000), clamp(size, 1, 50), Sort.by("createdAt").descending()));
        Map<String, Object> response = pageResponse(messages);
        response.put("keyword", searchTerm);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadFile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("roomId") Long roomId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "messageType", required = false) Message.MessageType messageType,
            @RequestParam(value = "replyToMessageId", required = false) Long replyToMessageId) throws Exception {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_MESSAGE_SEND);
        Message message = botGatewayService.postFileAsBot(bot, roomId, file, messageType, replyToMessageId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileId", message.getId());
        response.put("messageId", message.getId());
        response.put("chatRoomId", roomId);
        response.put("fileName", message.getFileName());
        response.put("fileType", message.getFileType());
        response.put("fileSize", message.getFileSize());
        response.put("message", MessageDto.fromEntity(message));
        return ResponseEntity.ok(ApiResponse.success("uploaded", response));
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<byte[]> downloadFile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long fileId) throws Exception {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_MESSAGE_READ);
        BotGatewayService.GatewayFileDownload file = botGatewayService.downloadFile(bot, fileId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName())
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }

    @PostMapping("/images/inspect")
    public ResponseEntity<ApiResponse<JsonNode>> inspectImage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_MESSAGE_READ);
        Long roomId = parseRoomId(body.get("roomId"));
        Long messageId = parseOptionalLong(firstPresent(body, "messageId", "message_id"));
        Integer latestIndex = parseOptionalInt(firstPresent(body, "latestIndex", "latest_index"));
        return ResponseEntity.ok(ApiResponse.success(botGatewayService.inspectRoomImage(bot, roomId, messageId, latestIndex)));
    }

    @GetMapping("/workspaces")
    public ResponseEntity<ApiResponse<Map<String, Object>>> workspaces(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_WORKSPACE_READ);
        List<WorkspaceDto> workspaces = botGatewayService.listWorkspaces(bot);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "botId", bot.getId(),
                "workspaces", workspaces,
                "count", workspaces.size())));
    }

    @PostMapping("/workspaces")
    public ResponseEntity<ApiResponse<WorkspaceDto>> createWorkspace(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody WorkspaceDto.CreateWorkspaceRequest request) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_WORKSPACE_WRITE);
        return ResponseEntity.ok(ApiResponse.success("workspace created",
                botGatewayService.createWorkspace(bot, request)));
    }

    @GetMapping("/workspaces/{workspaceId}/files")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listWorkspaceFiles(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long workspaceId,
            @RequestParam(required = false) Long folderId) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_WORKSPACE_READ);
        List<WorkspaceDto.FileDto> files = botGatewayService.listWorkspaceFiles(bot, workspaceId, folderId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workspaceId", workspaceId);
        if (folderId != null) {
            response.put("folderId", folderId);
        }
        response.put("files", files);
        response.put("count", files.size());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping(value = "/workspaces/{workspaceId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<WorkspaceDto.FileDto>> uploadWorkspaceFile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long workspaceId,
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false) String versionNote,
            @RequestParam("file") MultipartFile file) throws Exception {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_WORKSPACE_WRITE);
        return ResponseEntity.ok(ApiResponse.success("workspace file uploaded",
                botGatewayService.uploadWorkspaceFile(bot, workspaceId, folderId, versionNote, file)));
    }

    @GetMapping("/workspaces/{workspaceId}/files/{fileId}/download")
    public ResponseEntity<byte[]> downloadWorkspaceFile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long workspaceId,
            @PathVariable Long fileId) throws Exception {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_WORKSPACE_READ);
        WorkspaceDto.DownloadedWorkspaceFile download = botGatewayService.downloadWorkspaceFile(bot, workspaceId, fileId);
        WorkspaceFile file = download.getFile();
        String contentType = file.getMimeType() != null && !file.getMimeType().isBlank()
                ? file.getMimeType()
                : "application/octet-stream";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getDisplayName())
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(contentType))
                .body(download.getBytes());
    }

    @GetMapping("/workspaces/{workspaceId}/files/{fileId}/text")
    public ResponseEntity<ApiResponse<WorkspaceDto.TextContent>> readWorkspaceText(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long workspaceId,
            @PathVariable Long fileId) throws Exception {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_WORKSPACE_READ);
        return ResponseEntity.ok(ApiResponse.success(
                botGatewayService.readWorkspaceText(bot, workspaceId, fileId)));
    }

    @PostMapping("/workspaces/{workspaceId}/files/text")
    public ResponseEntity<ApiResponse<WorkspaceDto.FileDto>> createWorkspaceText(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long workspaceId,
            @RequestBody WorkspaceDto.CreateTextFileRequest request) throws Exception {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_WORKSPACE_WRITE);
        return ResponseEntity.ok(ApiResponse.success("workspace text file created",
                botGatewayService.createWorkspaceText(bot, workspaceId, request)));
    }

    @PostMapping("/workspaces/{workspaceId}/files/{fileId}/text")
    public ResponseEntity<ApiResponse<WorkspaceDto.FileDto>> saveWorkspaceText(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long workspaceId,
            @PathVariable Long fileId,
            @RequestBody WorkspaceDto.SaveTextRequest request) throws Exception {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_WORKSPACE_WRITE);
        return ResponseEntity.ok(ApiResponse.success("workspace text file saved",
                botGatewayService.saveWorkspaceText(bot, workspaceId, fileId, request)));
    }

    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createRoom(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_ROOM_MANAGE);
        String name = body.get("name") != null ? body.get("name").toString() : null;
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name required");
        }
        String description = body.get("description") != null ? body.get("description").toString() : null;
        ChatRoom room = botGatewayService.createRoom(bot, name, description, parseLongList(body.get("memberIds")));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("roomId", room.getId());
        response.put("name", room.getName());
        response.put("roomType", room.getRoomType());
        response.put("botId", bot.getId());
        return ResponseEntity.ok(ApiResponse.success("room created", response));
    }

    @PostMapping("/rooms/{roomId}/members")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addRoomMember(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long roomId,
            @RequestBody Map<String, Object> body) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_ROOM_MANAGE);
        Long userId = parseOptionalLong(firstPresent(body, "userId", "user_id"));
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required");
        }
        botGatewayService.addRoomMember(bot, roomId, userId);
        return ResponseEntity.ok(ApiResponse.success("member added", Map.of(
                "roomId", roomId,
                "userId", userId)));
    }

    @DeleteMapping("/rooms/{roomId}/members/{userId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> kickRoomMember(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long roomId,
            @PathVariable Long userId) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_ROOM_MANAGE);
        botGatewayService.kickRoomMember(bot, roomId, userId);
        return ResponseEntity.ok(ApiResponse.success("member kicked", Map.of(
                "roomId", roomId,
                "userId", userId)));
    }

    @PostMapping("/rooms/{roomId}/members/{userId}/mute")
    public ResponseEntity<ApiResponse<Map<String, Object>>> muteRoomMember(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long roomId,
            @PathVariable Long userId,
            @RequestBody(required = false) Map<String, Object> body) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_ROOM_MANAGE);
        boolean muted = body == null || !Boolean.FALSE.equals(body.get("muted"));
        botGatewayService.muteRoomMember(bot, roomId, userId, muted);
        return ResponseEntity.ok(ApiResponse.success(muted ? "member muted" : "member unmuted", Map.of(
                "roomId", roomId,
                "userId", userId,
                "muted", muted)));
    }

    @GetMapping("/friends")
    public ResponseEntity<ApiResponse<Map<String, Object>>> friends(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_FRIEND_SEND);
        List<Map<String, Object>> friends = botGatewayService.listFriends(bot).stream()
                .map(this::userSummary)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "friends", friends,
                "count", friends.size())));
    }

    @PostMapping("/friends/{userId}/messages")
    public ResponseEntity<ApiResponse<Map<String, Object>>> postFriendMessage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body) {
        BotConfig bot = authenticate(authorization, BotTokenService.SCOPE_FRIEND_SEND);
        String content = body.get("content") != null ? body.get("content").toString() : null;
        Long replyToMessageId = parseOptionalLong(firstPresent(body, "replyToMessageId", "reply_to_message_id"));
        Long fileId = parseOptionalLong(firstPresent(body, "fileId", "file_id", "imageFileId", "image_file_id"));
        Message message = botGatewayService.postFriendMessage(bot, userId, content, replyToMessageId, fileId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messageId", message.getId());
        response.put("chatRoomId", message.getChatRoom() != null ? message.getChatRoom().getId() : null);
        response.put("friendUserId", userId);
        response.put("message", MessageDto.fromEntity(message));
        return ResponseEntity.ok(ApiResponse.success("sent", response));
    }

    private BotConfig authenticate(String authorization, String requiredScope) {
        String token = extractToken(authorization);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bot token");
        }
        BotConfig bot = botTokenService.resolveBotByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid bot token"));
        if (!botTokenService.hasScope(bot, requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing bot token scope: " + requiredScope);
        }
        if (!rateLimitService.tryAcquire("botgw:" + bot.getId(), INBOUND_PER_MINUTE, Duration.ofMinutes(1))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "bot gateway rate limit exceeded");
        }
        return bot;
    }

    private String extractToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return value.isEmpty() ? null : value;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleGatewayStatus(ResponseStatusException ex) {
        String reason = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiResponse.error(ex.getStatusCode().value(), reason));
    }

    private Long parseRoomId(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId required");
    }

    private Object firstPresent(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key)) {
                return body.get(key);
            }
        }
        return null;
    }

    private Long parseOptionalLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            long parsed = number.longValue();
            return parsed > 0 ? parsed : null;
        }
        String text = value.toString().trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(text);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid numeric id");
        }
    }

    private Integer parseOptionalInt(Object value) {
        Long parsed = parseOptionalLong(value);
        return parsed == null ? null : parsed.intValue();
    }

    private List<Long> parseLongList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::parseOptionalLong)
                    .filter(id -> id != null)
                    .toList();
        }
        Long single = parseOptionalLong(value);
        return single == null ? List.of() : List.of(single);
    }

    private Map<String, Object> userSummary(User user) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", user.getId());
        summary.put("username", user.getUsername());
        summary.put("displayName", user.getDisplayName());
        summary.put("avatarUrl", user.getAvatarUrl());
        return summary;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private Map<String, Object> pageResponse(Page<Message> messages) {
        List<MessageDto> dtos = messages.getContent().stream()
                .map(MessageDto::fromEntity)
                .toList();
        return new java.util.LinkedHashMap<>(Map.of(
                "messages", dtos,
                "currentPage", messages.getNumber(),
                "totalPages", messages.getTotalPages(),
                "totalElements", messages.getTotalElements(),
                "hasNext", messages.hasNext(),
                "hasPrevious", messages.hasPrevious()));
    }
}
