package com.yulong.chatagent.user.controller;

import com.yulong.chatagent.model.common.ApiResponse;
import com.yulong.chatagent.user.application.AuthService;
import com.yulong.chatagent.user.model.request.LoginRequest;
import com.yulong.chatagent.user.model.request.RegisterRequest;
import com.yulong.chatagent.user.model.response.LoginResponse;
import com.yulong.chatagent.user.model.vo.LoginUserVO;
import com.yulong.chatagent.user.web.RefreshTokenCookieManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Slf4j
/**
 * HTTP entry points for authentication and "current user" queries.
 *
 * <p>This controller deliberately keeps business rules out of the web layer.
 * It delegates credential validation and token issuance to {@link AuthService},
 * then handles HTTP-specific concerns such as reading or writing cookies.</p>
 */
public class AuthController {
    private final AuthService authService;
    private final RefreshTokenCookieManager refreshTokenCookieManager;

    public AuthController(AuthService authService,
                          RefreshTokenCookieManager refreshTokenCookieManager) {
        this.authService = authService;
        this.refreshTokenCookieManager = refreshTokenCookieManager;
    }


    @PostMapping("auth/register")
    public ApiResponse<LoginResponse> register(@RequestBody RegisterRequest registerRequest,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        log.info("HTTP register received: uri={}, username={}",
                request.getRequestURI(),
                registerRequest.getUsername());
        LoginResponse loginResponse = authService.register(registerRequest);
        // The refresh token is not exposed in the JSON body. It is written to
        // an HttpOnly cookie so browser clients can refresh sessions safely.
        refreshTokenCookieManager.writeRefreshTokenCookie(request, response, loginResponse.getRefreshToken());
        return ApiResponse.success(loginResponse);
    }

    @PostMapping("auth/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest loginRequest,
                                            HttpServletRequest request,
                                            HttpServletResponse response) {
        log.info("HTTP login received: uri={}, username={}",
                request.getRequestURI(),
                loginRequest.getUsername());
        LoginResponse loginResponse = authService.login(loginRequest);
        // Login returns a short-lived access token in the body and stores the
        // long-lived refresh token in a cookie managed by the browser.
        refreshTokenCookieManager.writeRefreshTokenCookie(request, response, loginResponse.getRefreshToken());
        return ApiResponse.success(loginResponse);
    }

    @PostMapping("auth/refresh")
    public ApiResponse<LoginResponse> refresh(
            @CookieValue(name = RefreshTokenCookieManager.REFRESH_TOKEN_COOKIE_NAME, required = false)
            String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        log.debug("HTTP refresh received: uri={}, refreshToken={}",
                request.getRequestURI(),
                refreshToken);
        LoginResponse loginResponse = authService.refresh(refreshToken);
        // Refresh rotates the refresh token as well, so the new value must
        // replace the old cookie in the same response.
        refreshTokenCookieManager.writeRefreshTokenCookie(request, response, loginResponse.getRefreshToken());
        return ApiResponse.success(loginResponse);
    }

    @PostMapping("auth/logout")
    public ApiResponse<Void> logout(
            @CookieValue(name = RefreshTokenCookieManager.REFRESH_TOKEN_COOKIE_NAME, required = false)
            String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        log.debug("HTTP logout received: uri={}, refreshToken={}",
                request.getRequestURI(),
                refreshToken);
        authService.logout(refreshToken);
        // Always clear the browser cookie, even if the incoming refresh token
        // is already missing or invalid, so logout stays idempotent.
        refreshTokenCookieManager.clearRefreshTokenCookie(request, response);
        return ApiResponse.success();
    }

    @GetMapping("user/me")
    public ApiResponse<LoginUserVO> currentUser() {
        log.debug("HTTP currentUser received");
        return ApiResponse.success(authService.currentUser());
    }
}
