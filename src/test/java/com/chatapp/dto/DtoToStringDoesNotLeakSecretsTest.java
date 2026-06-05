package com.chatapp.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DtoToStringDoesNotLeakSecretsTest {

    private static final String SENTINEL_PW_VALUE = "SENTINEL_PW_VALUE";
    private static final String SENTINEL_HASH_VALUE = "SENTINEL_HASH_VALUE";
    private static final String SENTINEL_SALT_VALUE = "SENTINEL_SALT_VALUE";

    @Test
    void loginRequestToStringDoesNotLeakSecrets() {
        UserDto.LoginRequest request = new UserDto.LoginRequest(
                "alice", SENTINEL_PW_VALUE, SENTINEL_HASH_VALUE);

        assertNoSentinels(request.toString());
    }

    @Test
    void registerRequestToStringDoesNotLeakSecrets() {
        UserDto.RegisterRequest request = new UserDto.RegisterRequest(
                "alice", SENTINEL_PW_VALUE, "alice@example.com", null, "Alice",
                SENTINEL_HASH_VALUE, SENTINEL_SALT_VALUE, "m=65536,t=3,p=1,v=19,hashLen=32");

        assertNoSentinels(request.toString());
    }

    @Test
    void changePasswordRequestToStringDoesNotLeakSecrets() {
        UserDto.ChangePasswordRequest request = new UserDto.ChangePasswordRequest(
                SENTINEL_PW_VALUE, SENTINEL_HASH_VALUE, "NEW_" + SENTINEL_PW_VALUE,
                "NEW_" + SENTINEL_HASH_VALUE, SENTINEL_SALT_VALUE,
                "m=65536,t=3,p=1,v=19,hashLen=32");

        assertNoSentinels(request.toString());
    }

    @Test
    void mixedSentinelValuesDoNotLeakFromAnyPasswordDto() {
        assertNoSentinels(new UserDto.LoginRequest(
                "mixed", SENTINEL_PW_VALUE, SENTINEL_HASH_VALUE).toString());
        assertNoSentinels(new UserDto.RegisterRequest(
                "mixed", null, "mixed@example.com", null, null,
                SENTINEL_HASH_VALUE, SENTINEL_SALT_VALUE,
                "m=65536,t=3,p=1,v=19,hashLen=32").toString());
        assertNoSentinels(new UserDto.ChangePasswordRequest(
                null, SENTINEL_HASH_VALUE, null, "NEXT_" + SENTINEL_HASH_VALUE,
                SENTINEL_SALT_VALUE, "m=65536,t=3,p=1,v=19,hashLen=32").toString());
    }

    private void assertNoSentinels(String value) {
        assertFalse(value.contains(SENTINEL_PW_VALUE));
        assertFalse(value.contains(SENTINEL_HASH_VALUE));
        assertFalse(value.contains(SENTINEL_SALT_VALUE));
    }
}
