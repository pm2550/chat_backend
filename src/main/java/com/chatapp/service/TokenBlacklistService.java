package com.chatapp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "token:blacklist:";
    private final StringRedisTemplate redisTemplate;

    public void blacklistToken(String tokenId, long remainingExpirationMs) {
        if (tokenId == null || remainingExpirationMs <= 0) {
            return;
        }
        String key = BLACKLIST_PREFIX + tokenId;
        redisTemplate.opsForValue().set(key, "1", remainingExpirationMs, TimeUnit.MILLISECONDS);
        log.debug("Token {} added to blacklist, expires in {}ms", tokenId, remainingExpirationMs);
    }

    public boolean isBlacklisted(String tokenId) {
        if (tokenId == null) {
            return false;
        }
        String key = BLACKLIST_PREFIX + tokenId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
