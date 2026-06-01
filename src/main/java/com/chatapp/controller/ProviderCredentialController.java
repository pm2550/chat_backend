package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.ProviderCredentialDto;
import com.chatapp.entity.BotConfig;
import com.chatapp.service.ProviderCredentialService;
import com.chatapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/provider-credentials")
@RequiredArgsConstructor
public class ProviderCredentialController {

    private final ProviderCredentialService credentialService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProviderCredentialDto.Response>>> list(
            @RequestParam(required = false) BotConfig.LLMProvider provider,
            Authentication auth) {
        Long userId = currentUserId(auth);
        return ResponseEntity.ok(ApiResponse.success(credentialService.list(userId, provider)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProviderCredentialDto.Response>> create(
            @Valid @RequestBody ProviderCredentialDto.CreateRequest request,
            Authentication auth) {
        Long userId = currentUserId(auth);
        return ResponseEntity.ok(ApiResponse.success("凭据已保存", credentialService.create(userId, request)));
    }

    @PutMapping("/{credentialId}")
    public ResponseEntity<ApiResponse<ProviderCredentialDto.Response>> update(
            @PathVariable Long credentialId,
            @RequestBody ProviderCredentialDto.UpdateRequest request,
            Authentication auth) {
        Long userId = currentUserId(auth);
        return ResponseEntity.ok(ApiResponse.success("凭据已更新", credentialService.update(userId, credentialId, request)));
    }

    @DeleteMapping("/{credentialId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable Long credentialId,
            Authentication auth) {
        Long userId = currentUserId(auth);
        credentialService.delete(userId, credentialId);
        return ResponseEntity.ok(ApiResponse.<Void>success("凭据已删除", null));
    }

    private Long currentUserId(Authentication auth) {
        return userService.findUserByUsername(auth.getName()).getId();
    }
}
