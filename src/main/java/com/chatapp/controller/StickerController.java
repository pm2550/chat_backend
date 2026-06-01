package com.chatapp.controller;

import com.chatapp.dto.ApiResponse;
import com.chatapp.dto.StickerDto;
import com.chatapp.dto.UserDto;
import com.chatapp.service.StickerService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sticker-packs")
@RequiredArgsConstructor
public class StickerController {

    private final StickerService stickerService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<StickerDto.PackInfo>>> listPacks(Authentication auth) {
        UserDto user = userService.findByUsername(auth.getName());
        return ResponseEntity.ok(ApiResponse.success(stickerService.listPacks(user.getId())));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<StickerDto.PackInfo>> createPack(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "cover", required = false) MultipartFile cover,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "isPublic", defaultValue = "false") boolean isPublic,
            Authentication auth) throws Exception {
        UserDto user = userService.findByUsername(auth.getName());
        if (files != null && !files.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(
                    "贴纸包已上传",
                    stickerService.createPackFromFiles(user.getId(), name, isPublic, cover, files)));
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择贴纸文件");
        }
        return ResponseEntity.ok(ApiResponse.success(
                "贴纸包已上传",
                stickerService.createPack(user.getId(), name, isPublic, file)));
    }

    @GetMapping("/{packId}/stickers")
    public ResponseEntity<ApiResponse<List<StickerDto>>> listStickers(@PathVariable Long packId) {
        return ResponseEntity.ok(ApiResponse.success(stickerService.listStickers(packId)));
    }

    @PostMapping("/{packId}/subscribe")
    public ResponseEntity<ApiResponse<Void>> subscribe(@PathVariable Long packId, Authentication auth) {
        UserDto user = userService.findByUsername(auth.getName());
        stickerService.subscribe(user.getId(), packId);
        return ResponseEntity.ok(ApiResponse.success("已订阅贴纸包", null));
    }

    @DeleteMapping("/{packId}/subscribe")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(@PathVariable Long packId, Authentication auth) {
        UserDto user = userService.findByUsername(auth.getName());
        stickerService.unsubscribe(user.getId(), packId);
        return ResponseEntity.ok(ApiResponse.success("已取消订阅", null));
    }
}
