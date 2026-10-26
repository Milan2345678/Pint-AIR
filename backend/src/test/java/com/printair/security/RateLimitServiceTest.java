package com.printair.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTest {
    @Test
    void allowsConfiguredNumberOfRequestsWithinWindow() {
        var limiter = new RateLimitService();

        assertThat(limiter.allow("ip:127.0.0.1", 2, 60_000)).isTrue();
        assertThat(limiter.allow("ip:127.0.0.1", 2, 60_000)).isTrue();
        assertThat(limiter.allow("ip:127.0.0.1", 2, 60_000)).isFalse();
        assertThat(limiter.allow("ip:127.0.0.2", 2, 60_000)).isTrue();
    }
}
