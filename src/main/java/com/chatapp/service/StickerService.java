package com.chatapp.service;

import com.chatapp.dto.StickerDto;
import com.chatapp.entity.Sticker;
import com.chatapp.entity.StickerPack;
import com.chatapp.entity.StickerPackSubscription;
import com.chatapp.entity.User;
import com.chatapp.repository.StickerPackRepository;
import com.chatapp.repository.StickerPackSubscriptionRepository;
import com.chatapp.repository.StickerRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class StickerService {

    private final StickerPackRepository stickerPackRepository;
    private final StickerRepository stickerRepository;
    private final StickerPackSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;

    @Transactional(readOnly = true)
    public List<StickerDto.PackInfo> listPacks(Long userId) {
        return stickerPackRepository.findAvailableForUser(userId).stream()
                .map(StickerDto.PackInfo::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StickerDto> listStickers(Long packId) {
        return stickerRepository.findByPackIdOrderByIndexInPackAscIdAsc(packId).stream()
                .map(StickerDto::fromEntity)
                .toList();
    }

    @Transactional
    public StickerDto.PackInfo createPack(Long userId, String name, boolean isPublic, MultipartFile zipFile)
            throws IOException {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        StickerPack pack = new StickerPack();
        pack.setName(name == null || name.isBlank() ? "我的贴纸包" : name.trim());
        pack.setOwnerUser(owner);
        pack.setIsPublic(isPublic);
        pack = stickerPackRepository.save(pack);

        int index = 0;
        try (ZipInputStream input = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory() || !isStickerImage(entry.getName())) {
                    continue;
                }
                byte[] bytes = input.readAllBytes();
                String url = fileStorageService.uploadStickerFile(
                        entry.getName(),
                        contentType(entry.getName()),
                        bytes);
                Sticker sticker = new Sticker();
                sticker.setPack(pack);
                sticker.setUrl(url);
                sticker.setKeyword(baseName(entry.getName()));
                sticker.setIndexInPack(index++);
                stickerRepository.save(sticker);
                if (pack.getCoverUrl() == null) {
                    pack.setCoverUrl(url);
                }
                if (index >= 80) break;
            }
        }
        if (index == 0) {
            throw new IllegalArgumentException("压缩包里没有可用贴纸图片");
        }
        return StickerDto.PackInfo.fromEntity(stickerPackRepository.save(pack));
    }

    @Transactional
    public StickerDto.PackInfo createPackFromFiles(Long userId,
                                                   String name,
                                                   boolean isPublic,
                                                   MultipartFile cover,
                                                   List<MultipartFile> files) throws IOException {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        List<MultipartFile> usableFiles = files == null
                ? List.of()
                : files.stream()
                        .filter(file -> file != null && !file.isEmpty())
                        .toList();
        if (usableFiles.isEmpty()) {
            throw new IllegalArgumentException("至少选择 1 张贴纸图片");
        }
        if (usableFiles.size() > 24) {
            throw new IllegalArgumentException("单个贴纸包最多 24 张图片");
        }

        StickerPack pack = new StickerPack();
        pack.setName(name == null || name.isBlank() ? "我的贴纸包" : name.trim());
        pack.setOwnerUser(owner);
        pack.setIsPublic(isPublic);
        pack = stickerPackRepository.save(pack);

        if (cover != null && !cover.isEmpty()) {
            pack.setCoverUrl(fileStorageService.uploadStickerFile(
                    cover.getOriginalFilename(),
                    cover.getContentType(),
                    cover.getBytes()));
        }

        int index = 0;
        for (MultipartFile file : usableFiles) {
            String originalName = file.getOriginalFilename();
            if (!isStickerImage(originalName == null ? "" : originalName)) {
                throw new IllegalArgumentException("仅支持 png、jpg、webp 或 gif 贴纸图片");
            }
            if (file.getSize() > 256 * 1024L) {
                throw new IllegalArgumentException("单张贴纸不能超过 256KB: " + originalName);
            }
            String url = fileStorageService.uploadStickerFile(
                    originalName,
                    file.getContentType(),
                    file.getBytes());
            Sticker sticker = new Sticker();
            sticker.setPack(pack);
            sticker.setUrl(url);
            sticker.setKeyword(baseName(originalName));
            sticker.setIndexInPack(index++);
            stickerRepository.save(sticker);
            if (pack.getCoverUrl() == null) {
                pack.setCoverUrl(url);
            }
        }
        return StickerDto.PackInfo.fromEntity(stickerPackRepository.save(pack));
    }

    @Transactional
    public void subscribe(Long userId, Long packId) {
        if (subscriptionRepository.findByPackIdAndUserId(packId, userId).isPresent()) {
            return;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        StickerPack pack = stickerPackRepository.findById(packId)
                .orElseThrow(() -> new IllegalArgumentException("贴纸包不存在"));
        StickerPackSubscription subscription = new StickerPackSubscription();
        subscription.setUser(user);
        subscription.setPack(pack);
        subscriptionRepository.save(subscription);
    }

    @Transactional
    public void unsubscribe(Long userId, Long packId) {
        subscriptionRepository.deleteByPackIdAndUserId(packId, userId);
    }

    private boolean isStickerImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp");
    }

    private String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "image/png";
    }

    private String baseName(String name) {
        String normalized = name.replace('\\', '/');
        String file = normalized.substring(normalized.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
}
