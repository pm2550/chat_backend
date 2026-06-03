package com.chatapp.service;

import com.chatapp.dto.AppVersionDto;
import com.chatapp.entity.AppVersion;
import com.chatapp.entity.DeviceToken;
import com.chatapp.entity.User;
import com.chatapp.repository.AppVersionRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.websocket.RawWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppVersionService {

    private final AppVersionRepository versionRepository;
    private final UserRepository userRepository;
    private final RawWebSocketHandler webSocketHandler;

    @Value("${app.version.storage-path:./uploads/app-releases/}")
    private String storagePath;

    /**
     * Check if an update is available for the given platform.
     */
    public AppVersionDto.CheckResponse checkVersion(DeviceToken.Platform platform, int currentVersionCode) {
        Optional<AppVersion> latest = versionRepository
                .findFirstByPlatformAndIsActiveTrueOrderByVersionCodeDesc(platform);

        if (latest.isEmpty() || latest.get().getVersionCode() <= currentVersionCode) {
            return new AppVersionDto.CheckResponse(false, false, null, null, null, null, null);
        }

        AppVersion v = latest.get();
        return new AppVersionDto.CheckResponse(
                true,
                Boolean.TRUE.equals(v.getForceUpdate()),
                v.getVersionName(),
                v.getVersionCode(),
                v.getReleaseNotes(),
                v.getDownloadUrl(),
                v.getFileSize()
        );
    }

    /**
     * Publish a new version with an optional artifact file.
     */
    @Transactional
    public AppVersionDto publishVersion(AppVersionDto.PublishRequest request,
                                        MultipartFile artifact,
                                        Long publisherId) throws IOException {
        User publisher = userRepository.findById(publisherId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        AppVersion version = new AppVersion();
        version.setPlatform(request.getPlatform());
        version.setVersionName(request.getVersionName());
        version.setVersionCode(request.getVersionCode());
        version.setForceUpdate(Boolean.TRUE.equals(request.getForceUpdate()));
        version.setReleaseNotes(request.getReleaseNotes());
        version.setPublishedBy(publisher);
        version.setIsActive(true);

        if (artifact != null && !artifact.isEmpty()) {
            String platformDir = request.getPlatform().name().toLowerCase();
            Path dir = Paths.get(storagePath, platformDir);
            Files.createDirectories(dir);

            String filename = artifact.getOriginalFilename();
            if (filename == null || filename.isBlank()) {
                filename = "app-" + request.getVersionName() + "-" + request.getPlatform().name().toLowerCase();
            }
            Path target = dir.resolve(filename);
            Files.copy(artifact.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            version.setArtifactFilename(filename);
            version.setFileSize(artifact.getSize());
            version.setDownloadUrl("/api/v1/app/download/" + platformDir + "/" + filename);
        }

        // Deactivate older versions for this platform
        versionRepository.findByPlatformOrderByVersionCodeDesc(request.getPlatform())
                .stream()
                .filter(v -> Boolean.TRUE.equals(v.getIsActive()))
                .forEach(v -> {
                    v.setIsActive(false);
                    versionRepository.save(v);
                });

        version = versionRepository.save(version);
        log.info("发布版本: {} {} v{} (code={})",
                version.getPlatform(), version.getVersionName(), version.getVersionCode(),
                publisher.getUsername());

        try {
            webSocketHandler.broadcastAppUpdate(version);
        } catch (Exception e) {
            log.warn("版本发布成功但 WebSocket 更新推送失败: {}", e.getMessage());
        }

        return toDto(version);
    }

    /**
     * Publish a version from CI using a system/admin publisher identity.
     */
    @Transactional
    public AppVersionDto publishVersionFromCi(AppVersionDto.PublishRequest request,
                                             MultipartFile artifact) throws IOException {
        User publisher = userRepository.findByUsername("system")
                .or(() -> userRepository.findFirstByRolesContainingOrderByIdAsc(User.Role.ADMIN))
                .orElseThrow(() -> new RuntimeException("未找到 system 或 ADMIN 发布用户"));
        return publishVersion(request, artifact, publisher.getId());
    }

    public Path getArtifactPath(String platform, String filename) {
        Path path = Paths.get(storagePath, platform, filename).normalize();
        if (!path.startsWith(Paths.get(storagePath).normalize())) {
            throw new SecurityException("非法路径");
        }
        return path;
    }

    public List<AppVersionDto> listVersions(DeviceToken.Platform platform) {
        return versionRepository.findByPlatformOrderByVersionCodeDesc(platform)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    private AppVersionDto toDto(AppVersion v) {
        AppVersionDto dto = new AppVersionDto();
        dto.setId(v.getId());
        dto.setPlatform(v.getPlatform() != null ? v.getPlatform().name() : null);
        dto.setVersionName(v.getVersionName());
        dto.setVersionCode(v.getVersionCode());
        dto.setForceUpdate(v.getForceUpdate());
        dto.setReleaseNotes(v.getReleaseNotes());
        dto.setDownloadUrl(v.getDownloadUrl());
        dto.setFileSize(v.getFileSize());
        dto.setCreatedAt(v.getCreatedAt());
        return dto;
    }
}
