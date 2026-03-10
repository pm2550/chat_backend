package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.UserDto;
import com.chatapp.entity.DeviceToken;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.UserService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final PushNotificationService pushNotificationService;
    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> registerToken(
            @RequestBody RegisterTokenRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        pushNotificationService.registerDeviceToken(
                currentUser.getId(), request.getToken(), request.getPlatform(), request.getDeviceInfo());
        return ResponseEntity.ok(ApiResponse.<Void>success("设备令牌注册成功", null));
    }

    @PostMapping("/unregister")
    public ResponseEntity<ApiResponse<Void>> unregisterToken(@RequestBody UnregisterTokenRequest request) {
        pushNotificationService.unregisterDeviceToken(request.getToken());
        return ResponseEntity.ok(ApiResponse.<Void>success("设备令牌已注销", null));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterTokenRequest {
        @NotBlank
        private String token;
        @NotNull
        private DeviceToken.Platform platform;
        private String deviceInfo;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnregisterTokenRequest {
        @NotBlank
        private String token;
    }
}
