package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.MemoryDto;
import com.chatapp.dto.UserDto;
import com.chatapp.entity.MemoryEntry;
import com.chatapp.service.MemoryService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Memory library REST API (Phase 5a / F2). All endpoints are room-scoped and
 * membership-gated by {@link MemoryService}; the same store backs the bot
 * save_memory / recall_memory tools.
 */
@RestController
@RequestMapping("/api/v1/rooms/{roomId}/memories")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<MemoryDto>>> list(
            @PathVariable Long roomId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            Authentication auth) {
        Long userId = currentUserId(auth);
        List<MemoryEntry> entries = (q != null && !q.isBlank())
                ? memoryService.searchForUser(roomId, userId, q, 20)
                : memoryService.listForUser(roomId, userId, includeArchived);
        return ResponseEntity.ok(ApiResponse.success(entries.stream().map(MemoryDto::from).toList()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MemoryDto>> create(
            @PathVariable Long roomId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long userId = currentUserId(auth);
        MemoryEntry created = memoryService.createForUser(
                roomId, userId,
                str(body.get("title")),
                str(body.get("content")),
                str(body.get("keywords")),
                parseVisibility(body.get("visibility")));
        return ResponseEntity.ok(ApiResponse.success("记忆已保存", MemoryDto.from(created)));
    }

    @PutMapping("/{memoryId}")
    public ResponseEntity<ApiResponse<MemoryDto>> update(
            @PathVariable Long roomId,
            @PathVariable Long memoryId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long userId = currentUserId(auth);
        MemoryEntry updated = memoryService.update(
                memoryId, userId,
                str(body.get("title")),
                str(body.get("content")),
                str(body.get("keywords")),
                parseVisibility(body.get("visibility")));
        return ResponseEntity.ok(ApiResponse.success("记忆已更新", MemoryDto.from(updated)));
    }

    @PostMapping("/{memoryId}/pin")
    public ResponseEntity<ApiResponse<MemoryDto>> pin(
            @PathVariable Long roomId,
            @PathVariable Long memoryId,
            @RequestBody(required = false) Map<String, Object> body,
            Authentication auth) {
        Long userId = currentUserId(auth);
        boolean pinned = body == null || !body.containsKey("pinned")
                || Boolean.parseBoolean(String.valueOf(body.get("pinned")));
        MemoryEntry entry = memoryService.setPinned(memoryId, userId, pinned);
        return ResponseEntity.ok(ApiResponse.success(MemoryDto.from(entry)));
    }

    @PostMapping("/{memoryId}/archive")
    public ResponseEntity<ApiResponse<MemoryDto>> archive(
            @PathVariable Long roomId,
            @PathVariable Long memoryId,
            @RequestBody(required = false) Map<String, Object> body,
            Authentication auth) {
        Long userId = currentUserId(auth);
        boolean archived = body == null || !body.containsKey("archived")
                || Boolean.parseBoolean(String.valueOf(body.get("archived")));
        MemoryEntry entry = memoryService.setArchived(memoryId, userId, archived);
        return ResponseEntity.ok(ApiResponse.success(MemoryDto.from(entry)));
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long roomId,
            @PathVariable Long memoryId,
            Authentication auth) {
        memoryService.delete(memoryId, currentUserId(auth));
        return ResponseEntity.ok(ApiResponse.<Void>success("记忆已删除", null));
    }

    private Long currentUserId(Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        return currentUser.getId();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static MemoryEntry.Visibility parseVisibility(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return MemoryEntry.Visibility.valueOf(raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null; // unknown value -> let the service default it
        }
    }
}
