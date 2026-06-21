package com.brucecli.web.fetch;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkPolicyTest {
    @Test
    void rejectsUnsafeSchemesAndLocalHosts() {
        NetworkPolicy policy = new NetworkPolicy();

        assertThrows(IllegalArgumentException.class, () -> policy.validateUrl("file:///tmp/secret"));
        assertThrows(IllegalArgumentException.class, () -> policy.validateUrl("http://localhost:8080/admin"));
        assertThrows(IllegalArgumentException.class, () -> policy.validateUrl("http://127.0.0.1:8080/admin"));
        assertDoesNotThrow(() -> policy.validateUrl("https://example.com/docs"));
    }

    @Test
    void rateLimitsFetchCalls() {
        NetworkPolicy policy = new NetworkPolicy(1, Duration.ofMinutes(1), Clock.systemUTC());

        policy.acquire();

        assertThrows(IllegalStateException.class, policy::acquire);
    }
}
