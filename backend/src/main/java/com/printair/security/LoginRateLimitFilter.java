package com.printair.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {
    private final RateLimitService rateLimit;

    public LoginRateLimitFilter(RateLimitService rateLimit) {
        this.rateLimit = rateLimit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().equals("/api/auth/login")
                && !rateLimit.allow("login:" + request.getRemoteAddr(), 10, 60_000)) {
            response.sendError(429, "Too many login attempts");
            return;
        }
        chain.doFilter(request, response);
    }
}
