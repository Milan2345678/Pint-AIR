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
        String path = request.getRequestURI();
        String action = switch (path) {
            case "/api/auth/login" -> "login";
            case "/api/auth/register" -> "register";
            case "/api/auth/resend-verification" -> "resend-verification";
            case "/api/auth/verify-email" -> "verify";
            default -> null;
        };
        if (action != null) {
            int limit = switch (action) {
                case "login" -> 10;
                case "verify" -> 20;
                default -> 5;
            };
            if (!rateLimit.allow(action + ":" + request.getRemoteAddr(), limit, 60_000)) {
                response.sendError(429, "Too many authentication attempts");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
