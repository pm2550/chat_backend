package com.chatapp.controller;

import com.chatapp.dto.AppVersionDto;
import com.chatapp.entity.DeviceToken;
import com.chatapp.service.AppVersionService;
import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/app")
@RequiredArgsConstructor
@Slf4j
public class AppVersionController {

    private final AppVersionService versionService;
    private final UserService userService;

    /**
     * Public — called before login. Returns whether an update is available.
     */
    @GetMapping("/version")
    public ResponseEntity<?> checkVersion(
            @RequestParam String platform,
            @RequestParam(defaultValue = "0") int currentVersionCode) {
        try {
            DeviceToken.Platform p = DeviceToken.Platform.valueOf(platform.toUpperCase());
            AppVersionDto.CheckResponse resp = versionService.checkVersion(p, currentVersionCode);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "不支持的平台: " + platform));
        }
    }

    /**
     * Public — stream an artifact file for download.
     */
    @GetMapping("/download/{platform}/{filename:.+}")
    public ResponseEntity<Resource> download(
            @PathVariable String platform,
            @PathVariable String filename) throws IOException {
        Path path = versionService.getArtifactPath(platform, filename);
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new UrlResource(path.toUri());
        String contentType = Files.probeContentType(path);
        if (contentType == null) contentType = "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(resource);
    }

    /**
     * Admin — publish a new version (multipart: metadata JSON + optional artifact file).
     */
    @PostMapping("/version/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> publishVersion(
            @RequestPart("metadata") AppVersionDto.PublishRequest request,
            @RequestPart(value = "artifact", required = false) MultipartFile artifact,
            Authentication auth) {
        try {
            var user = userService.findUserByUsername(auth.getName());
            AppVersionDto dto = versionService.publishVersion(request, artifact, user.getId());
            return ResponseEntity.ok(Map.of("message", "版本发布成功", "version", dto));
        } catch (Exception e) {
            log.error("版本发布失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Admin — list all versions for a platform.
     */
    @GetMapping("/version/list")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> listVersions(@RequestParam String platform) {
        try {
            DeviceToken.Platform p = DeviceToken.Platform.valueOf(platform.toUpperCase());
            List<AppVersionDto> versions = versionService.listVersions(p);
            return ResponseEntity.ok(versions);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "不支持的平台: " + platform));
        }
    }
}
