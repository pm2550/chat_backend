package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.ImageGenerationDto;
import com.chatapp.service.ImageGenerationService;
import com.chatapp.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
public class ImageGenerationController {
    private final ImageGenerationService imageGenerationService;
    private final UserService userService;

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<ImageGenerationDto.GenerateResponse>> generate(
            @Valid @RequestBody ImageGenerationDto.GenerateRequest request,
            Authentication auth) {
        Long userId = userService.findUserByUsername(auth.getName()).getId();
        return ResponseEntity.ok(ApiResponse.success(
                "图片生成任务已提交",
                imageGenerationService.submit(userId, request)));
    }
}
