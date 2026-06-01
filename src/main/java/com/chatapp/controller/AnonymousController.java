package com.chatapp.controller;

import com.chatapp.dto.AnonymousDto;
import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.UserDto;
import com.chatapp.service.AnonymousRerollQuotaService;
import com.chatapp.service.AnonymousService;
import com.chatapp.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}/anonymous")
@RequiredArgsConstructor
public class AnonymousController {

    private final AnonymousService anonymousService;
    private final UserService userService;
    private final AnonymousRerollQuotaService rerollQuotaService;

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

    @PostMapping("/reroll")
    public ResponseEntity<ApiResponse<AnonymousDto>> rerollAnonymous(
            @PathVariable Long roomId,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        AnonymousRerollQuotaService.QuotaSnapshot quota;
        try {
            quota = rerollQuotaService.consume(currentUser.getId());
        } catch (AnonymousRerollQuotaService.QuotaExceededException ex) {
            return quotaExceeded(ex.getSnapshot());
        }
        try {
            AnonymousDto result = anonymousService.rerollAnonymousIdentity(currentUser.getId(), roomId);
            result.setDailyRemaining(quota.getRemaining());
            result.setQuotaResetsAt(quota.getResetsAt());
            return ResponseEntity.ok(ApiResponse.success("匿名身份已重新抽取", result));
        } catch (RuntimeException ex) {
            rerollQuotaService.release(currentUser.getId());
            throw ex;
        }
    }

    private ResponseEntity<ApiResponse<AnonymousDto>> quotaExceeded(
            AnonymousRerollQuotaService.QuotaSnapshot quota) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(quota.getRetryAfterSeconds()))
                .body(ApiResponse.error(429, "今日匿名身份切换次数已用完，请明天再试"));
    }

    @GetMapping("/themes")
    public ResponseEntity<ApiResponse<List<AnonymousDto.ThemeInfo>>> listThemes() {
        return ResponseEntity.ok(ApiResponse.success(anonymousService.listThemes()));
    }

    @PutMapping("/theme")
    public ResponseEntity<ApiResponse<AnonymousDto.ThemeInfo>> updateTheme(
            @PathVariable Long roomId,
            @RequestBody AnonymousDto.ThemeRequest request,
            Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        AnonymousDto.ThemeInfo result = anonymousService.updateRoomTheme(
                roomId,
                currentUser.getId(),
                request.getThemeKey());
        return ResponseEntity.ok(ApiResponse.success("匿名主题已切换", result));
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
