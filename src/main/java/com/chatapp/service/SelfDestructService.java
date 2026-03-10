package com.chatapp.service;

import com.chatapp.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class SelfDestructService {

    private final MessageRepository messageRepository;

    @Scheduled(fixedRate = 30000) // Run every 30 seconds
    @Transactional
    public void destroyExpiredMessages() {
        LocalDateTime now = LocalDateTime.now();
        int deleted = messageRepository.deleteExpiredSelfDestructMessages(now);
        if (deleted > 0) {
            log.info("已销毁 {} 条过期自毁消息", deleted);
        }
    }
}
