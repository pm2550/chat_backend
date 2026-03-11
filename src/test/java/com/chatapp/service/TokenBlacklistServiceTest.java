package com.chatapp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TokenBlacklistService tokenBlacklistService;

    @Test
    void testBlacklistToken() {
        String tokenId = "abc-123";
        long remainingMs = 3600000L;

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        tokenBlacklistService.blacklistToken(tokenId, remainingMs);

        verify(valueOperations).set("token:blacklist:abc-123", "1", 3600000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void testBlacklistToken_NullTokenId() {
        tokenBlacklistService.blacklistToken(null, 3600000L);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void testBlacklistToken_ZeroExpiration() {
        tokenBlacklistService.blacklistToken("abc-123", 0L);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void testBlacklistToken_NegativeExpiration() {
        tokenBlacklistService.blacklistToken("abc-123", -1L);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void testIsBlacklisted_True() {
        String tokenId = "abc-123";

        when(redisTemplate.hasKey("token:blacklist:abc-123")).thenReturn(Boolean.TRUE);

        boolean result = tokenBlacklistService.isBlacklisted(tokenId);

        assertTrue(result);
        verify(redisTemplate).hasKey("token:blacklist:abc-123");
    }

    @Test
    void testIsBlacklisted_False() {
        String tokenId = "abc-123";

        when(redisTemplate.hasKey("token:blacklist:abc-123")).thenReturn(Boolean.FALSE);

        boolean result = tokenBlacklistService.isBlacklisted(tokenId);

        assertFalse(result);
    }

    @Test
    void testIsBlacklisted_NullTokenId() {
        boolean result = tokenBlacklistService.isBlacklisted(null);

        assertFalse(result);
        verifyNoInteractions(redisTemplate);
    }
}
