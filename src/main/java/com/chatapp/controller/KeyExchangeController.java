package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.KeyBundleDto;
import com.chatapp.dto.UserDto;
import com.chatapp.service.KeyExchangeService;
import com.chatapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/keys")
@RequiredArgsConstructor
public class KeyExchangeController {

    private final KeyExchangeService keyExchangeService;
    private final UserService userService;

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<KeyBundleDto>> uploadKeyBundle(
            @Valid @RequestBody KeyBundleDto.UploadRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        KeyBundleDto result = keyExchangeService.uploadKeyBundle(currentUser.getId(), request);
        return ResponseEntity.ok(ApiResponse.success("密钥上传成功", result));
    }

    @GetMapping("/bundle/{userId}")
    public ResponseEntity<ApiResponse<KeyBundleDto>> getKeyBundle(@PathVariable Long userId) {
        KeyBundleDto result = keyExchangeService.getKeyBundle(userId);
        return ResponseEntity.ok(ApiResponse.success("获取密钥成功", result));
    }

    @GetMapping("/exists/{userId}")
    public ResponseEntity<ApiResponse<Boolean>> hasKeyBundle(@PathVariable Long userId) {
        boolean exists = keyExchangeService.hasKeyBundle(userId);
        return ResponseEntity.ok(ApiResponse.success(exists));
    }

    @DeleteMapping("/my-keys")
    public ResponseEntity<ApiResponse<Void>> deleteMyKeyBundle(Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        keyExchangeService.deleteKeyBundle(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.<Void>success("密钥已删除", null));
    }
}
