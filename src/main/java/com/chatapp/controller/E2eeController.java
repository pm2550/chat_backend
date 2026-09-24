package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.E2eeDto;
import com.chatapp.entity.User;
import com.chatapp.service.E2eeKeyService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 私聊端到端加密：密钥保管、公钥目录、会话能否加密。
 * 旧的 /api/v1/keys/* 是之前的假实现，新客户端不再调用。
 */
@RestController
@RequestMapping("/api/v1/e2ee")
@RequiredArgsConstructor
public class E2eeController {

    private final E2eeKeyService e2eeKeyService;
    private final UserService userService;

    /** 本人的全部密钥（公钥 + 包装过的私钥），登录后用密码解开。 */
    @GetMapping("/keys/me")
    public ResponseEntity<ApiResponse<E2eeDto.OwnKeys>> myKeys(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(e2eeKeyService.ownKeys(currentUser(auth).getId())));
    }

    @PostMapping("/keys")
    public ResponseEntity<ApiResponse<E2eeDto.OwnKeys>> createKey(
            @RequestBody E2eeDto.CreateKeyRequest request,
            Authentication auth) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    "端到端加密已开启",
                    e2eeKeyService.createKey(currentUser(auth).getId(), request)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(409, e.getMessage()));
        }
    }

    @PutMapping("/enabled")
    public ResponseEntity<ApiResponse<E2eeDto.OwnKeys>> setEnabled(
            @RequestBody E2eeDto.SetEnabledRequest request,
            Authentication auth) {
        boolean enabled = request != null && Boolean.TRUE.equals(request.getEnabled());
        return ResponseEntity.ok(ApiResponse.success(
                enabled ? "端到端加密已开启" : "新消息将不再加密",
                e2eeKeyService.setEnabled(currentUser(auth).getId(), enabled)));
    }

    @GetMapping("/users/{userId}/keys")
    public ResponseEntity<ApiResponse<E2eeDto.UserKeys>> userKeys(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.success(e2eeKeyService.userKeys(userId)));
    }

    @GetMapping("/rooms/{roomId}/status")
    public ResponseEntity<ApiResponse<E2eeDto.RoomStatus>> roomStatus(
            @PathVariable Long roomId,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                e2eeKeyService.roomStatus(roomId, currentUser(auth).getId())));
    }

    private User currentUser(Authentication auth) {
        return userService.findUserByUsername(auth.getName());
    }
}
