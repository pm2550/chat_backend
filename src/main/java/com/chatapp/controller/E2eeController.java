package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.E2eeDto;
import com.chatapp.entity.User;
import com.chatapp.service.E2eeKeyService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.LockedException;
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

    /**
     * 设置 / 重新生成恢复码：只收"用恢复码包装过的私钥"，恢复码本身从不经过服务器。
     * 恢复码包装随 {@code GET /keys/me} 返回给本人，公钥目录里没有。
     */
    @PutMapping("/recovery")
    public ResponseEntity<ApiResponse<E2eeDto.OwnKeys>> setRecovery(
            @RequestBody E2eeDto.SetRecoveryRequest request,
            Authentication auth) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    "恢复码已设置",
                    e2eeKeyService.setRecoveryWraps(currentUser(auth).getId(), request)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(409, e.getMessage()));
        }
    }

    /**
     * 用恢复码找回私钥后，换上用现在的登录密码包装的新密码包装。要带当前密码的 clientHash：
     * 密码不对返回 422（不用 401，免得客户端当成登录过期），尝试过多返回 429。
     */
    @PutMapping("/keys/password-wraps")
    public ResponseEntity<ApiResponse<E2eeDto.OwnKeys>> rewrapWithCurrentPassword(
            @RequestBody E2eeDto.PasswordRewrapRequest request,
            Authentication auth) {
        User user = currentUser(auth);
        if (!UserService.SCHEME_CLIENT.equals(user.getPasswordScheme())) {
            throw new IllegalArgumentException("请先在新版客户端修改一次登录密码");
        }
        try {
            if (request == null || !userService.matchesCurrentClientHash(user, request.getClientHash())) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(ApiResponse.error(422, "登录密码不正确"));
            }
        } catch (LockedException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error(429, e.getMessage()));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "加密密钥已改用当前密码保护",
                e2eeKeyService.replacePasswordWraps(user.getId(), request.getWraps())));
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
