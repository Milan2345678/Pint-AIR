package com.printair.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginRateLimitFilterTest {
    @Test
    void allowsTenLoginAttemptsPerMinute() throws Exception {
        RateLimitService limits = mock(RateLimitService.class);
        when(limits.allow("login:127.0.0.1", 10, 60_000)).thenReturn(true);
        var filter = new LoginRateLimitFilter(limits);
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(limits).allow("login:127.0.0.1", 10, 60_000);
        verify(chain).doFilter(request, response);
    }

    @Test
    void registrationAndResendUseSeparateLimitsFromLogin() throws Exception {
        RateLimitService limits = mock(RateLimitService.class);
        when(limits.allow("register:127.0.0.1", 5, 60_000)).thenReturn(false);
        var filter = new LoginRateLimitFilter(limits);
        var request = new MockHttpServletRequest("POST", "/api/auth/register");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(chain, never()).doFilter(request, response);
        verify(limits).allow(eq("register:127.0.0.1"), eq(5), eq(60_000L));
    }

    @Test
    void resendUsesASeparateBucketFromRegistration() throws Exception {
        RateLimitService limits = mock(RateLimitService.class);
        when(limits.allow("resend-verification:127.0.0.1", 5, 60_000)).thenReturn(true);
        var filter = new LoginRateLimitFilter(limits);
        var request = new MockHttpServletRequest("POST", "/api/auth/resend-verification");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(limits).allow("resend-verification:127.0.0.1", 5, 60_000);
        verify(limits, never()).allow("register:127.0.0.1", 5, 60_000);
    }

    @Test
    void verificationAttemptsUseTheirOwnLimit() throws Exception {
        RateLimitService limits = mock(RateLimitService.class);
        when(limits.allow("verify:127.0.0.1", 20, 60_000)).thenReturn(true);
        var filter = new LoginRateLimitFilter(limits);
        var request = new MockHttpServletRequest("POST", "/api/auth/verify-email");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(limits).allow("verify:127.0.0.1", 20, 60_000);
    }
}
