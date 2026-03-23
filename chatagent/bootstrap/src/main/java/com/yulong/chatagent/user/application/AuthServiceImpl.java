package com.yulong.chatagent.user.application;

import com.yulong.chatagent.agent.application.DefaultAgentProvisioningService;
import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.context.LoginUser;
import com.yulong.chatagent.exception.BizException;
import com.yulong.chatagent.user.converter.UserConverter;
import com.yulong.chatagent.user.model.dto.UserDTO;
import com.yulong.chatagent.user.model.request.LoginRequest;
import com.yulong.chatagent.user.model.request.RegisterRequest;
import com.yulong.chatagent.user.model.response.LoginResponse;
import com.yulong.chatagent.user.model.vo.LoginUserVO;
import com.yulong.chatagent.user.port.RefreshTokenStore;
import com.yulong.chatagent.user.port.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
@Slf4j
/**
 * Coordinates the full authentication lifecycle:
 * registration, login, refresh, logout, and current-user lookup.
 *
 * <p>The service owns session rules, while the controller only translates
 * HTTP requests into service calls.</p>
 */
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenStore refreshTokenStore;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long refreshTtlDays;
    private final UserConverter userConverter;
    private final DefaultAgentProvisioningService defaultAgentProvisioningService;

    public AuthServiceImpl(UserRepository userRepository,
                           PasswordService passwordService,
                           JwtTokenService jwtTokenService,
                           RefreshTokenStore refreshTokenStore,
                           @Value("${auth.jwt.refresh-ttl-days}") long refreshTtlDays,
                           UserConverter userConverter,
                           DefaultAgentProvisioningService defaultAgentProvisioningService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenStore = refreshTokenStore;
        this.refreshTtlDays = refreshTtlDays;
        this.userConverter = userConverter;
        this.defaultAgentProvisioningService = defaultAgentProvisioningService;
    }

    @Override
    public LoginResponse register(RegisterRequest request) {
        // Registration reuses the normal login issuance path so a new account
        // immediately receives both tokens and can enter the application.
        Assert.notNull(request, "RegisterRequest must not be null");
        Assert.hasText(request.getUsername(), "RegisterRequest.username must not be blank");
        Assert.hasText(request.getPassword(), "RegisterRequest.password must not be blank");
        String username = request.getUsername().trim();
        log.info("Register requested: username={}, password={}, passwordLength={}",
                username,
                request.getPassword(),
                request.getPassword().length());

        UserDTO existingUser = userRepository.findByUsername(username);
        if (existingUser != null) {
            log.warn("Register rejected: username already exists, username={}",
                    username);
            throw new BizException("Username already exists");
        }

        UserDTO user = UserDTO.builder()
                .username(username)
                .passwordHash(passwordService.hash(request.getPassword()))
                .role("user")
                .build();

        boolean saved = userRepository.save(user);
        if (!saved || user.getId() == null || user.getId().isBlank()) {
            log.error("Register failed to persist user: username={}", username);
            throw new BizException("Failed to register user");
        }

        log.info("Register succeeded: userId={}, username={}", user.getId(), username);
        defaultAgentProvisioningService.ensureForUser(user.getId());
        // Registration immediately creates a logged-in session for the new user.
        return issueLoginResponse(user);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        // This project currently enforces one active refresh-token session per user.
        // A successful login clears the old server-side session before issuing a new one.
        Assert.notNull(request, "LoginRequest must not be null");
        Assert.hasText(request.getUsername(), "LoginRequest.username must not be blank");
        Assert.hasText(request.getPassword(), "LoginRequest.password must not be blank");
        String username = request.getUsername().trim();
        log.info("Login requested: username={}, password={}, passwordLength={}",
                username,
                request.getPassword(),
                request.getPassword().length());
        UserDTO user = userRepository.findByUsername(username);
        if (user == null || !passwordService.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Login failed: invalid credentials, username={}", username);
            throw new BizException("Invalid username or password");
        }
        defaultAgentProvisioningService.ensureForUser(user.getId());
        refreshTokenStore.deleteByUserId(user.getId());
        log.info("Login succeeded: userId={}, username={}", user.getId(), username);
        return issueLoginResponse(user);
    }

    @Override
    public LoginResponse refresh(String refreshToken) {
        // Refresh is intentionally passwordless. The refresh token is the only
        // credential that proves the caller owns an existing renewable session.
        if (refreshToken == null || refreshToken.isBlank()) {
            log.info("Refresh rejected: missing refresh token");
            throw new BizException("Invalid refresh token");
        }
        log.info("Refresh requested: refreshToken={}", refreshToken);
        String userId = refreshTokenStore.getUserId(refreshToken);
        if(userId == null) {
            log.warn("Refresh failed: token not found, refreshToken={}",
                    refreshToken);
            throw new BizException("Invalid refresh token");
        }
        UserDTO user = userRepository.findById(userId);
        if (user == null) {
            log.warn("Refresh failed: user not found, userId={}, refreshToken={}",
                    userId,
                    refreshToken);
            throw new BizException("Cannot find user with id " + userId);
        }
        String newAccessToken = jwtTokenService.generateAccessToken(userConverter.toJwtClaims(user));
        String newRefreshToken = generateRefreshToken();
        // Rotate the refresh token on every successful refresh to reduce replay risk.
        refreshTokenStore.save(newRefreshToken, userId, Duration.ofDays(refreshTtlDays));
        refreshTokenStore.delete(refreshToken);
        log.info("Refresh succeeded: userId={}, username={}, oldRefreshToken={}, newRefreshToken={}",
                userId,
                user.getUsername(),
                refreshToken,
                newRefreshToken);
        return userConverter.toLoginResponse(user, newAccessToken, newRefreshToken);
    }

    @Override
    public void logout(String refreshToken) {
        // Logout only revokes the refresh token. Already-issued access tokens
        // remain usable until their short expiration time is reached.
        if (refreshToken == null || refreshToken.isBlank()) {
            log.debug("Logout skipped: missing refresh token");
            return;
        }
        log.info("Logout requested: refreshToken={}", refreshToken);
        refreshTokenStore.delete(refreshToken);
        log.info("Logout completed: refreshToken={}", refreshToken);
    }

    @Override
    public LoginUserVO currentUser() {
        // The interceptor has already authenticated the request and populated
        // UserContext. This method only translates that internal model into
        // the response model used by the API.
        LoginUser loginUser = UserContext.requireUser();
        log.info("Current user requested: userId={}, username={}",
                loginUser.getUserId(),
                loginUser.getUsername());
        return userConverter.toLoginUserVO(loginUser);
    }

    private LoginResponse issueLoginResponse(UserDTO user) {
        String accessToken = jwtTokenService.generateAccessToken(userConverter.toJwtClaims(user));
        String refreshToken = generateRefreshToken();
        // The access token is stateless; the refresh token is the server-side
        // handle that lets the session be renewed or revoked later.
        refreshTokenStore.save(refreshToken, user.getId(), Duration.ofDays(refreshTtlDays));
        log.info("Issued login response: userId={}, username={}, accessToken={}, refreshToken={}",
                user.getId(),
                user.getUsername(),
                accessToken,
                refreshToken);
        return userConverter.toLoginResponse(user, accessToken, refreshToken);
    }

    private String generateRefreshToken() {
        // A refresh token is just a high-entropy opaque string. The server
        // treats it as a lookup key instead of embedding user claims into it.
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }


}
