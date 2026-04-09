package com.yulong.chatagent.user.config;

import com.yulong.chatagent.context.UserContext;
import com.yulong.chatagent.user.application.AuthenticatedUserSnapshotCache;
import com.yulong.chatagent.user.application.JwtTokenService;
import com.yulong.chatagent.user.converter.UserConverter;
import com.yulong.chatagent.user.model.UserStatus;
import com.yulong.chatagent.user.model.dto.JwtClaims;
import com.yulong.chatagent.user.model.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@Slf4j
/**
 * Protects authenticated endpoints by validating the access token from the
 * Authorization header and populating {@link UserContext}.
 */
public class JwtAuthenticationInterceptor implements HandlerInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SSE_TOKEN_PARAMETER = "access_token";

    private final JwtTokenService jwtTokenService;
    private final UserConverter userConverter;
    private final AuthenticatedUserSnapshotCache authenticatedUserSnapshotCache;

    public JwtAuthenticationInterceptor(JwtTokenService jwtTokenService,
                                        UserConverter userConverter,
                                        AuthenticatedUserSnapshotCache authenticatedUserSnapshotCache) {
        this.jwtTokenService = jwtTokenService;
        this.userConverter = userConverter;
        this.authenticatedUserSnapshotCache = authenticatedUserSnapshotCache;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        // Servlet threads are reused, so always clear any stale user state first.
        UserContext.clear();

        String accessToken = resolveAccessToken(request);
        if (!StringUtils.hasText(accessToken)) {
            log.info("JWT auth rejected: missing access token, method={}, uri={}",
                    request.getMethod(),
                    request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing access token");
            return false;
        }

        if (!jwtTokenService.isAccessTokenValid(accessToken)) {
            log.warn("JWT auth rejected: invalid access token, method={}, uri={}",
                    request.getMethod(),
                    request.getRequestURI());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid access token");
            return false;
        }

        JwtClaims claims = jwtTokenService.parseAccessToken(accessToken);
        UserDTO user = authenticatedUserSnapshotCache.getByUserId(claims.getUserId());
        if (user == null || Boolean.TRUE.equals(user.getDeleted()) || UserStatus.DISABLED.matches(user.getStatus())) {
            log.warn("JWT auth rejected: user unavailable, method={}, uri={}, userId={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    claims.getUserId());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "User session is no longer valid");
            return false;
        }

        // Store the latest authenticated user snapshot so authorization uses
        // the current role from persistence instead of stale token claims.
        UserContext.set(userConverter.toLoginUser(user));
        log.info("JWT auth accepted: method={}, uri={}, userId={}, username={}",
                request.getMethod(),
                request.getRequestURI(),
                user.getId(),
                user.getUsername());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        // Guarantee context cleanup even when the handler throws.
        UserContext.clear();
    }

    private String resolveAccessToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return resolveSseAccessToken(request);
        }
        // Keep header parsing in one place so the token service only handles
        // raw JWT strings and stays independent from HTTP formatting details.
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    private String resolveSseAccessToken(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/sse/")) {
            return null;
        }
        // Native EventSource cannot set Authorization headers, so SSE falls
        // back to an explicit access-token query parameter.
        String accessToken = request.getParameter(SSE_TOKEN_PARAMETER);
        if (!StringUtils.hasText(accessToken)) {
            return null;
        }
        return accessToken.trim();
    }
}
