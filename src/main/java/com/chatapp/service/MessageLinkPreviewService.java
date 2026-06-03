package com.chatapp.service;

import com.chatapp.dto.UrlPreviewDto;
import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageLinkPreviewService {
    private static final Pattern HTTPS_URL_PATTERN = Pattern.compile(
            "https://[^\\s<>()]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_PUNCTUATION_PATTERN = Pattern.compile(
            "[\\])}.,;!?，。！？、]+$");

    private final MessageRepository messageRepository;
    private final UrlPreviewService urlPreviewService;
    private final ObjectMapper objectMapper;

    @Async
    @Transactional
    public void enrichMessage(Long messageId, String content) {
        String url = firstHttpsUrl(content);
        if (url == null) {
            return;
        }

        try {
            UrlPreviewDto preview = urlPreviewService.fetch(url);
            String previewJson = objectMapper.writeValueAsString(preview);
            messageRepository.findById(messageId).ifPresent(message -> {
                if (message.getLinkPreviewJson() != null && !message.getLinkPreviewJson().isBlank()) {
                    return;
                }
                if (Boolean.TRUE.equals(message.getIsDeleted())) {
                    return;
                }
                message.setLinkPreviewJson(previewJson);
                messageRepository.save(message);
            });
        } catch (Exception e) {
            log.debug("Skipped link preview enrichment for message {}: {}", messageId, e.getMessage());
        }
    }

    String firstHttpsUrl(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher matcher = HTTPS_URL_PATTERN.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        String url = matcher.group();
        return TRAILING_PUNCTUATION_PATTERN.matcher(url).replaceAll("");
    }
}
