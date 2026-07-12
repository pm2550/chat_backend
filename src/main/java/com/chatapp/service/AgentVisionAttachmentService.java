package com.chatapp.service;

import com.chatapp.dto.BotDto;
import com.chatapp.entity.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Resolves image messages into LLM-safe multimodal data URLs.
 *
 * All file bytes are read through {@link FileStorageService}, so FileVault and
 * workspace object decryption stay centralized in the existing storage layer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentVisionAttachmentService {
    private final FileStorageService fileStorageService;

    public boolean isImageMessage(Message message) {
        if (message == null) {
            return false;
        }
        if (message.getMessageType() == Message.MessageType.IMAGE
                || message.getMessageType() == Message.MessageType.IMAGE_GENERATION) {
            return true;
        }
        String fileType = message.getFileType();
        if (fileType != null && fileType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return true;
        }
        String name = firstText(message.getFileName(), message.getContent(), fileNameFromUrl(message.getFileUrl()));
        return hasImageExtension(name);
    }

    public ImageContext resolve(Message message, boolean includeBinary) {
        if (!isImageMessage(message)) {
            return ImageContext.empty();
        }
        String fileName = firstText(message.getFileName(), message.getContent(), fileNameFromUrl(message.getFileUrl()), "image");
        String mediaType = mediaType(message, fileName);
        String annotation = "[图片: " + fileName + "]";
        if (!includeBinary) {
            return new ImageContext(List.of(), annotation, false);
        }
        try {
            byte[] bytes = readImageBytes(message);
            String dataUrl = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
            return new ImageContext(List.of(new BotDto.ImageAttachment(fileName, mediaType, dataUrl)), annotation, false);
        } catch (Exception e) {
            log.warn("Agent vision image decode failed messageId={} file='{}' url='{}': {}",
                    message.getId(), fileName, message.getFileUrl(), e.getMessage());
            return new ImageContext(List.of(), "[图片读取失败: " + fileName + "]", true);
        }
    }

    private byte[] readImageBytes(Message message) throws IOException {
        StorageRef ref = storageRef(message);
        return fileStorageService.getFile(ref.type(), ref.fileName());
    }

    private StorageRef storageRef(Message message) {
        String fileUrl = message.getFileUrl();
        if (fileUrl != null && !fileUrl.isBlank()) {
            if (fileUrl.startsWith("workspace:")) {
                return new StorageRef("workspace", decode(fileUrl.substring("workspace:".length())));
            }
            String marker = "/api/files/";
            int markerAt = fileUrl.indexOf(marker);
            String path = markerAt >= 0 ? fileUrl.substring(markerAt + marker.length()) : fileUrl;
            if (path.startsWith("api/files/")) {
                path = path.substring("api/files/".length());
            }
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            int slash = path.indexOf('/');
            if (slash > 0) {
                String type = path.substring(0, slash);
                String name = path.substring(slash + 1);
                int queryAt = name.indexOf('?');
                if (queryAt >= 0) {
                    name = name.substring(0, queryAt);
                }
                if (isSupportedType(type) && !name.isBlank()) {
                    return new StorageRef(type, decode(name));
                }
            }
        }
        return new StorageRef("chat", firstText(message.getFileUrl(), message.getFileName(), message.getContent(), ""));
    }

    private boolean isSupportedType(String type) {
        return "chat".equals(type)
                || "image-gen".equals(type)
                || "background".equals(type)
                || "avatar".equals(type)
                || "workspace".equals(type);
    }

    private String fileNameFromUrl(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return "";
        }
        String stripped = fileUrl;
        int queryAt = stripped.indexOf('?');
        if (queryAt >= 0) {
            stripped = stripped.substring(0, queryAt);
        }
        int slash = stripped.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < stripped.length()) {
            return decode(stripped.substring(slash + 1));
        }
        if (stripped.startsWith("workspace:")) {
            return decode(stripped.substring("workspace:".length()));
        }
        return decode(stripped);
    }

    private String mediaType(Message message, String fileName) {
        String fileType = message.getFileType();
        if (fileType != null && fileType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return fileType;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    private boolean hasImageExtension(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public record ImageContext(List<BotDto.ImageAttachment> attachments, String annotation, boolean damaged) {
        static ImageContext empty() {
            return new ImageContext(List.of(), "", false);
        }
    }

    private record StorageRef(String type, String fileName) {
    }
}
