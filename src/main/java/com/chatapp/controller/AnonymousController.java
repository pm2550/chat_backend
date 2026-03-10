package com.chatapp.controller;

import com.chatapp.dto.AnonymousDto;
import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.UserDto;
import com.chatapp.service.AnonymousService;
import com.chatapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}/anonymous")
@RequiredArgsConstructor
public class AnonymousController {

    private final AnonymousService anonymousService;
    private final UserService userService;

    @PostMapping("/enter")
    public ResponseEntity<ApiResponse<AnonymousDto>> enterAnonymousMode(
            @PathVariable Long roomId,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        AnonymousDto result = anonymousService.getOrCreateIdentity(currentUser.getId(), roomId);
        return ResponseEntity.ok(ApiResponse.success("已进入匿名模式", result));
    }

    @PutMapping("/rename")
    public ResponseEntity<ApiResponse<AnonymousDto>> renameAnonymous(
            @PathVariable Long roomId,
            @Valid @RequestBody AnonymousDto.RenameRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        AnonymousDto result = anonymousService.renameAnonymousIdentity(
                currentUser.getId(), roomId, request.getNewName());
        return ResponseEntity.ok(ApiResponse.success("改名成功", result));
    }

    @PutMapping("/toggle")
    public ResponseEntity<ApiResponse<Void>> toggleAnonymous(
            @PathVariable Long roomId,
            @RequestParam boolean enable,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        anonymousService.toggleAnonymous(roomId, currentUser.getId(), enable);
        String msg = enable ? "匿名功能已开启" : "匿名功能已关闭";
        return ResponseEntity.ok(ApiResponse.<Void>success(msg, null));
    }
}
