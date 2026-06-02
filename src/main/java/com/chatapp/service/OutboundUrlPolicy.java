package com.chatapp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Principal-aware SSRF guard for server-side outbound HTTP.
 *
 * <p>The key distinction (which a single "block all private ranges" check gets
 * wrong) is <em>who supplied the URL</em>:
 * <ul>
 *   <li>{@link Caller#OWNER_CONFIGURED} — a target set in server config / env by the
 *       deployment owner (e.g. the dashscope-proxy, SearXNG, the OpenClaw gateway).
 *       Fully trusted; internal hosts are allowed.</li>
 *   <li>{@link Caller#USER_SUPPLIED} — a URL supplied by a user or an external bot
 *       (a webhook callback_url, a BYO provider base_url, a bot-supplied media URL).
 *       Must NOT be able to reach internal/private hosts (SSRF), and must be https,
 *       <em>unless</em> the host is on the owner-curated internal allowlist (so the
 *       owner can legitimately point a webhook at 172.17.0.1 / localhost).</li>
 * </ul>
 *
 * <p>This is the single shared policy intended for the external-bot webhook
 * dispatcher, the BYO base_url validation, and bot media/card URL checks, so those
 * features do not each re-implement an inconsistent allow/deny list.
 */
@Service
public class OutboundUrlPolicy {

    public enum Caller {
        OWNER_CONFIGURED,
        USER_SUPPLIED
    }

    private static final Set<String> DEFAULT_ALLOWED_INTERNAL_HOSTS =
            Set.of("127.0.0.1", "localhost", "172.17.0.1");

    @Value("${outbound-url.owner-allowed-internal-hosts:127.0.0.1,localhost,172.17.0.1}")
    private String ownerAllowedInternalHostsRaw;

    private volatile Set<String> cachedAllowedHosts;

    /** Non-throwing variant. */
    public boolean isAllowed(String rawUrl, Caller caller) {
        try {
            assertAllowed(rawUrl, caller);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Validates {@code rawUrl} for the given caller and returns the parsed {@link URI}.
     *
     * @throws IllegalArgumentException if the URL is malformed, uses a non-HTTP(S)
     *         scheme, resolves to an internal host that the caller may not reach, or
     *         (for user-supplied URLs to public hosts) is not https.
     */
    public URI assertAllowed(String rawUrl, Caller caller) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("url 格式无效");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("仅支持 http/https 链接");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("url 主机无效");
        }

        boolean hostAllowlisted = allowedInternalHosts().contains(host.toLowerCase(Locale.ROOT));
        boolean internal = resolvesToInternalAddress(host);

        if (internal && !hostAllowlisted && caller == Caller.USER_SUPPLIED) {
            throw new IllegalArgumentException("不允许访问内网地址");
        }
        if (caller == Caller.USER_SUPPLIED
                && !hostAllowlisted
                && !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException("用户提供的外部链接仅支持 https");
        }
        return uri;
    }

    private boolean resolvesToInternalAddress(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()   // includes 169.254.x metadata range
                        || address.isSiteLocalAddress()   // 10/8, 172.16/12, 192.168/16
                        || address.isMulticastAddress()) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            // Cannot verify the host is public — treat as unsafe.
            throw new IllegalArgumentException("url 主机无法解析");
        }
    }

    private Set<String> allowedInternalHosts() {
        Set<String> cached = cachedAllowedHosts;
        if (cached == null) {
            String raw = ownerAllowedInternalHostsRaw;
            if (raw == null || raw.isBlank()) {
                cached = DEFAULT_ALLOWED_INTERNAL_HOSTS;
            } else {
                cached = Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
            }
            cachedAllowedHosts = cached;
        }
        return cached;
    }
}
