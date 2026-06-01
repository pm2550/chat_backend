package com.chatapp.controller;

import com.chatapp.dto.UrlPreviewDto;
import com.chatapp.service.UrlPreviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping({"/api/v1/url-preview", "/api/url-preview"})
@RequiredArgsConstructor
@Slf4j
public class UrlPreviewController {
    private final UrlPreviewService urlPreviewService;

    @PostMapping
    public ResponseEntity<?> preview(@RequestBody UrlPreviewDto.Request request) {
        try {
            if (request == null || request.getUrl() == null || request.getUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "url 不能为空"));
            }
            UrlPreviewDto preview = urlPreviewService.fetch(request.getUrl());
            return ResponseEntity.ok(Map.of(
                    "message", "链接预览生成成功",
                    "data", preview));
        } catch (Exception e) {
            log.warn("链接预览生成失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
