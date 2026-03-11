package com.chatapp.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilsTest {

    private JwtUtils jwtUtils;

    private static final String JWT_SECRET = "Y2hhdEFwcFNlY3JldEtleUZvckpXVFRva2VuU2lnbmluZ1RoaXNJc0FMb25nZXJLZXlGb3JTZWN1cml0eQ==";
    private static final long ACCESS_TOKEN_EXPIRATION_MS = 900000L;       // 15 min
    private static final long REFRESH_TOKEN_EXPIRATION_MS = 604800000L;   // 7 days

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", JWT_SECRET);
        ReflectionTestUtils.setField(jwtUtils, "accessTokenExpirationMs", ACCESS_TOKEN_EXPIRATION_MS);
        ReflectionTestUtils.setField(jwtUtils, "refreshTokenExpirationMs", REFRESH_TOKEN_EXPIRATION_MS);
    }

    @Test
    void testGenerateAccessToken() {
        String token = jwtUtils.generateAccessToken("testuser");

        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void testGenerateRefreshToken() {
        String token = jwtUtils.generateRefreshToken("testuser");

        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void testGetUserNameFromToken() {
        String token = jwtUtils.generateAccessToken("testuser");

        String username = jwtUtils.getUserNameFromJwtToken(token);

        assertEquals("testuser", username);
    }

    @Test
    void testGetTokenType_Access() {
        String token = jwtUtils.generateAccessToken("testuser");

        String type = jwtUtils.getTokenType(token);

        assertEquals("access", type);
    }

    @Test
    void testGetTokenType_Refresh() {
        String token = jwtUtils.generateRefreshToken("testuser");

        String type = jwtUtils.getTokenType(token);

        assertEquals("refresh", type);
    }

    @Test
    void testGetTokenId() {
        String token = jwtUtils.generateAccessToken("testuser");

        String tokenId = jwtUtils.getTokenId(token);

        assertNotNull(tokenId);
        assertFalse(tokenId.isEmpty());
    }

    @Test
    void testValidateJwtToken_Valid() {
        String token = jwtUtils.generateAccessToken("testuser");

        assertTrue(jwtUtils.validateJwtToken(token));
    }

    @Test
    void testValidateJwtToken_Invalid() {
        assertFalse(jwtUtils.validateJwtToken("this.is.not.a.valid.token"));
    }

    @Test
    void testValidateJwtToken_Expired() {
        // Temporarily set expiration to 0 so the token is immediately expired
        ReflectionTestUtils.setField(jwtUtils, "accessTokenExpirationMs", 0L);

        String token = jwtUtils.generateAccessToken("testuser");

        assertFalse(jwtUtils.validateJwtToken(token));

        // Reset to original value
        ReflectionTestUtils.setField(jwtUtils, "accessTokenExpirationMs", ACCESS_TOKEN_EXPIRATION_MS);
    }

    @Test
    void testRefreshAccessToken_WithRefreshToken() {
        String refreshToken = jwtUtils.generateRefreshToken("testuser");

        String newAccessToken = jwtUtils.refreshAccessToken(refreshToken);

        assertNotNull(newAccessToken);
        assertEquals("testuser", jwtUtils.getUserNameFromJwtToken(newAccessToken));
        assertEquals("access", jwtUtils.getTokenType(newAccessToken));
    }

    @Test
    void testRefreshAccessToken_WithAccessToken() {
        String accessToken = jwtUtils.generateAccessToken("testuser");

        assertThrows(RuntimeException.class, () -> jwtUtils.refreshAccessToken(accessToken));
    }

    @Test
    void testLegacyGenerateJwtToken() {
        String token = jwtUtils.generateJwtToken("testuser");

        assertNotNull(token);
        assertEquals("access", jwtUtils.getTokenType(token));
        assertEquals("testuser", jwtUtils.getUserNameFromJwtToken(token));
    }
}
