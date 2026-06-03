package com.chatapp.service;

import com.chatapp.service.OutboundUrlPolicy.Caller;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundUrlPolicyTest {

    private final OutboundUrlPolicy policy = new OutboundUrlPolicy();

    @Test
    void userSuppliedHttpsPublicHostAllowed() {
        // Literal public IP avoids any DNS dependency in tests.
        assertTrue(policy.isAllowed("https://8.8.8.8/hook", Caller.USER_SUPPLIED));
    }

    @Test
    void userSuppliedPlainHttpPublicHostRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.assertAllowed("http://8.8.8.8/hook", Caller.USER_SUPPLIED));
        assertFalse(policy.isAllowed("http://8.8.8.8/hook", Caller.USER_SUPPLIED));
    }

    @Test
    void userSuppliedInternalHostRejected() {
        // 10/8 is site-local and NOT on the allowlist -> SSRF blocked.
        assertThrows(IllegalArgumentException.class,
                () -> policy.assertAllowed("http://10.0.0.5:8080/x", Caller.USER_SUPPLIED));
    }

    @Test
    void userSuppliedAllowlistedInternalHostAllowed() {
        // Owner-curated internal hosts (SearXNG / dashscope-proxy / loopback) are reachable
        // even for user-supplied URLs, and the https requirement is waived for them.
        assertTrue(policy.isAllowed("http://172.17.0.1:8888/search", Caller.USER_SUPPLIED));
        assertTrue(policy.isAllowed("http://127.0.0.1:9094/v1", Caller.USER_SUPPLIED));
        assertTrue(policy.isAllowed("http://localhost/dashscope/v1", Caller.USER_SUPPLIED));
    }

    @Test
    void ownerConfiguredMayReachInternalHosts() {
        assertTrue(policy.isAllowed("http://10.0.0.5:8080/x", Caller.OWNER_CONFIGURED));
        assertTrue(policy.isAllowed("https://8.8.8.8/x", Caller.OWNER_CONFIGURED));
    }

    @Test
    void nonHttpSchemesRejectedForBothCallers() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.assertAllowed("file:///etc/passwd", Caller.USER_SUPPLIED));
        assertThrows(IllegalArgumentException.class,
                () -> policy.assertAllowed("ftp://8.8.8.8/x", Caller.OWNER_CONFIGURED));
    }

    @Test
    void blankAndMalformedRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.assertAllowed("", Caller.USER_SUPPLIED));
        assertThrows(IllegalArgumentException.class,
                () -> policy.assertAllowed("https://", Caller.USER_SUPPLIED));
    }
}
