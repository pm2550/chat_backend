package com.chatapp.controller;

import com.chatapp.dto.AnonymousDto;
import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.UserDto;
import com.chatapp.service.AnonymousRerollQuotaService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/anonymous", "/api/anonymous"})
@RequiredArgsConstructor
public class AnonymousQuotaController {

    private final AnonymousRerollQuotaService rerollQuotaService;
    private final UserService userService;

    @GetMapping("/quota")
    public ResponseEntity<ApiResponse<AnonymousDto.QuotaInfo>> quota(Authentication auth) {
        UserDto currentUser = userService.findByUsername(auth.getName());
        AnonymousRerollQuotaService.QuotaSnapshot quota =
                rerollQuotaService.quota(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(new AnonymousDto.QuotaInfo(
                quota.getUsed(),
                quota.getRemaining(),
                quota.getResetsAt())));
    }
}
