package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.UserDto;
import com.chatapp.dto.WebPushDto;
import com.chatapp.service.PushNotificationService;
import com.chatapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/push/web")
@RequiredArgsConstructor
public class WebPushController {

    private final PushNotificationService pushNotificationService;
    private final UserService userService;

    @GetMapping("/vapid-public-key")
    public ResponseEntity<ApiResponse<WebPushDto.VapidPublicKeyResponse>> vapidPublicKey() {
        return ResponseEntity.ok(ApiResponse.success(pushNotificationService.getWebPushPublicKey()));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<ApiResponse<Void>> subscribe(
            @Valid @RequestBody WebPushDto.SubscribeRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        pushNotificationService.subscribeWebPush(currentUser.getId(), request);
        return ResponseEntity.ok(ApiResponse.<Void>success("Web Push 订阅已开启", null));
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(
            @Valid @RequestBody WebPushDto.UnsubscribeRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        pushNotificationService.unsubscribeWebPush(currentUser.getId(), request);
        return ResponseEntity.ok(ApiResponse.<Void>success("Web Push 订阅已关闭", null));
    }
}
