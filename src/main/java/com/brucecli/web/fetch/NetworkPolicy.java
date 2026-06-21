package com.brucecli.web.fetch;

import okhttp3.HttpUrl;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * WebFetch 的网络安全策略。
 */
public class NetworkPolicy {
    private static final int DEFAULT_REQUESTS_PER_WINDOW = 10;
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    private final int maxRequestsPerWindow;
    private final Duration window;
    private final Clock clock;
    private int remainingTokens;
    private long windowStartedAtMillis;

    public NetworkPolicy() {
        this(DEFAULT_REQUESTS_PER_WINDOW, DEFAULT_WINDOW, Clock.systemUTC());
    }

    public NetworkPolicy(int maxRequestsPerWindow, Duration window, Clock clock) {
        if (maxRequestsPerWindow <= 0) {
            throw new IllegalArgumentException("maxRequestsPerWindow 必须大于 0");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window 必须为正数");
        }
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.window = window;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.remainingTokens = maxRequestsPerWindow;
        this.windowStartedAtMillis = this.clock.millis();
    }

    public HttpUrl validateUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        HttpUrl url = HttpUrl.parse(rawUrl.trim());
        if (url == null) {
            throw new IllegalArgumentException("URL 不合法: " + rawUrl);
        }
        String scheme = url.scheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("仅允许访问 http/https URL");
        }
        checkHost(url.host());
        return url;
    }

    public synchronized void acquire() {
        refillIfNeeded();
        if (remainingTokens <= 0) {
            throw new IllegalStateException("WebFetch 请求过于频繁，请稍后再试");
        }
        remainingTokens--;
    }

    private void checkHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少 host");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lower) || lower.endsWith(".localhost")) {
            throw new IllegalArgumentException("禁止访问 localhost");
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isBlocked(address)) {
                    throw new IllegalArgumentException("禁止访问内网、环回或链路本地地址: " + address.getHostAddress());
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 URL host: " + host);
        }
    }

    private boolean isBlocked(InetAddress address) {
        return address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()
            || isUniqueLocalIpv6(address);
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private void refillIfNeeded() {
        long now = clock.millis();
        if (now - windowStartedAtMillis >= window.toMillis()) {
            remainingTokens = maxRequestsPerWindow;
            windowStartedAtMillis = now;
        }
    }
}
