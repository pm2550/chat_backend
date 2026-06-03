package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.UserDto;
import com.chatapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserTitleController {

    private final UserService userService;

    @PutMapping("/me/title")
    public ResponseEntity<ApiResponse<UserDto>> updateMyTitle(
            @Valid @RequestBody UserDto.TitleRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        UserDto result = userService.updateTitle(currentUser.getId(), request);
        return ResponseEntity.ok(ApiResponse.success("头衔已更新", result));
    }

    @PutMapping("/{userId}/title")
    public ResponseEntity<ApiResponse<UserDto>> updateUserTitle(
            @PathVariable Long userId,
            @Valid @RequestBody UserDto.TitleRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        UserDto result = userService.updateUserTitleAsAdmin(currentUser.getId(), userId, request);
        return ResponseEntity.ok(ApiResponse.success("用户头衔已更新", result));
    }
}
