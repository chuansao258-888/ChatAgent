package com.yulong.chatagent.user.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Duration;

@Component
@Slf4j
/**
 * Centralizes all refresh-token cookie writes so the application uses one
 * consistent cookie name, path, TTL, and security policy.
 */
public class RefreshTokenCookieManager {

    public static final String REFRESH_TOKEN_COOKIE_NAME = "refreshToken";

    private final long refreshTtlDays;

    public RefreshTokenCookieManager(@Value("${auth.jwt.refresh-ttl-days}") long refreshTtlDays) {
        this.refreshTtlDays = refreshTtlDays;
    }

    public void writeRefreshTokenCookie(HttpServletRequest request,
                                        HttpServletResponse response,
                                        String refreshToken) {
        Assert.notNull(request, "HttpServletRequest cannot be null");
        Assert.notNull(response, "HttpServletResponse cannot be null");
        Assert.hasText(refreshToken, "refreshToken cannot be blank");

        // Scope the refresh token cookie to auth endpoints only so normal API
        // calls do not send a long-lived credential on every request.
        ResponseCookie cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ofDays(refreshTtlDays))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        log.info("Refresh token cookie written: path=/api/auth, secure={}, refreshToken={}",
                request.isSecure(),
                refreshToken);
    }

    public void clearRefreshTokenCookie(HttpServletRequest request, HttpServletResponse response) {
        Assert.notNull(request, "HttpServletRequest cannot be null");
        Assert.notNull(response, "HttpServletResponse cannot be null");

        // Clearing uses the same cookie attributes so the browser replaces the
        // stored cookie instead of keeping the old one.
        ResponseCookie cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        log.info("Refresh token cookie cleared: path=/api/auth, secure={}", request.isSecure());
    }
}
